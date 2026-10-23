package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class PrintWorkPhase {
    WAITING_NAME, PREPARING, CANCELLING, SENDING, AUTH_REQUIRED, ACCEPTED, FAILED, UNKNOWN, CANCELLED,
}

internal data class PrintWorkState(
    val requestId: Long,
    val request: PrintWorkRequest,
    val phase: PrintWorkPhase,
    val result: PrintResult? = null,
    val jobStatus: IppJobStatus? = null,
    val checkingStatus: Boolean = false,
    val statusError: String? = null,
)

/** Keeps a single print request running while the screen is recreated. */
internal object PrintWorkManager {
    val gate = PrintRequestGate()

    @Volatile
    var state: PrintWorkState? = null
        private set

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work -> Thread(work, "ipp-print-worker") }
    private val statusWorker = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "ipp-job-status").apply { isDaemon = true }
    }
    private var statusTask: ScheduledFuture<*>? = null
    private var statusGeneration = 0L
    private var runningId: Long? = null
    private var workerThread: Thread? = null
    @Volatile private var cancelRequested: Long? = null
    private var journal: android.content.SharedPreferences? = null
    var interruptedWorkMessage: String? = null
        private set

    @Synchronized fun initialize(context: Context) {
        if (journal != null) return
        journal = context.getSharedPreferences("print_work_journal", Context.MODE_PRIVATE)
        val phase = journal?.getString("phase", null)
        interruptedWorkMessage = when (phase) {
            "SENDING", "UNKNOWN" -> "이전 전송의 접수 여부를 확인할 수 없습니다. 프린터에서 출력 여부를 확인한 뒤 다시 인쇄하세요."
            "ACCEPTED" -> "이전 인쇄 요청은 접수됐지만 앱 종료로 출력 결과를 확인하지 못했습니다. 프린터에서 확인하세요."
            "PREPARING", "WAITING_NAME", "AUTH_REQUIRED" -> "앱 종료로 이전 문서 준비가 중단되었습니다. 자동으로 다시 전송하지 않습니다."
            else -> null
        }
    }

    private fun record(phase: PrintWorkPhase): Boolean = journal?.edit()?.putString("phase", phase.name)?.commit() ?: true
    private var observerOwner: Any? = null
    private var observer: ((PrintWorkState) -> Unit)? = null
    private var stateVersion = 0L
    private var deliveredVersion = -1L

    // Queued notifications retain only this singleton. The current screen is looked up on delivery.
    private val dispatchState = Runnable {
        val delivery = synchronized(lock) {
            val current = state
            val callback = observer
            if (current == null || callback == null || deliveredVersion == stateVersion) null
            else {
                deliveredVersion = stateVersion
                callback to current
            }
        }
        delivery?.let { (callback, current) -> callback(current) }
    }

    fun reserve(request: PrintWorkRequest): Long? {
        val id = synchronized(lock) {
            if (gate.isBusy()) return null
            check(record(PrintWorkPhase.WAITING_NAME)) { "인쇄 작업 상태를 저장할 수 없습니다" }
            statusGeneration += 1
            statusTask?.cancel(false)
            interruptedWorkMessage = null
            cancelRequested = null
            val reserved = gate.tryStart() ?: return null
            runningId = null
            replaceStateLocked(PrintWorkState(reserved, request, PrintWorkPhase.WAITING_NAME))
            reserved
        }
        notifyObserver()
        return id
    }

    fun submit(context: Context, id: Long) {
        val applicationContext = context.applicationContext
        val request = synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id || runningId != null ||
                current.phase !in setOf(PrintWorkPhase.WAITING_NAME, PrintWorkPhase.AUTH_REQUIRED)) return
            runningId = id
            replaceStateLocked(current.copy(phase = PrintWorkPhase.PREPARING, result = null))
            current.request
        }
        notifyObserver()
        try {
            worker.execute { perform(applicationContext, id, request) }
        } catch (error: Exception) {
            complete(id, failure("출력 요청을 시작할 수 없습니다: ${error.message}"))
        }
    }

    /** Preparation is interruptible; an already submitted job is never reported as locally cancelled. */
    fun cancel(id: Long) {
        synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id) return
            if (runningId == id && current.phase == PrintWorkPhase.PREPARING) {
                cancelRequested = id
                workerThread?.interrupt()
                replaceStateLocked(current.copy(phase = PrintWorkPhase.CANCELLING))
                notifyObserver()
                return
            }
            if (runningId != null || current.phase !in setOf(PrintWorkPhase.WAITING_NAME, PrintWorkPhase.AUTH_REQUIRED)) return
            gate.finish(id)
            record(PrintWorkPhase.CANCELLED)
            replaceStateLocked(current.copy(phase = PrintWorkPhase.CANCELLED))
        }
        notifyObserver()
    }

    fun failPending(id: Long, message: String) {
        synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id || runningId != null ||
                current.phase !in setOf(PrintWorkPhase.WAITING_NAME, PrintWorkPhase.AUTH_REQUIRED)) return
            gate.finish(id)
            record(PrintWorkPhase.FAILED)
            replaceStateLocked(current.copy(phase = PrintWorkPhase.FAILED, result = failure(message)))
        }
        notifyObserver()
    }

    fun observe(owner: Any, callback: (PrintWorkState) -> Unit) {
        synchronized(lock) {
            observerOwner = owner
            observer = callback
            deliveredVersion = -1L
        }
        notifyObserver()
    }

    fun removeObserver(owner: Any) {
        synchronized(lock) {
            if (observerOwner === owner) {
                observerOwner = null
                observer = null
            }
        }
    }

    private fun perform(applicationContext: Context, id: Long, request: PrintWorkRequest) {
        synchronized(lock) { workerThread = Thread.currentThread() }
        val result = try {
            check(cancelRequested != id) { "문서 준비를 취소했습니다" }
            check(record(PrintWorkPhase.PREPARING)) { "인쇄 작업 상태를 저장할 수 없습니다" }
            check(gate.isActive(id)) { "인쇄 요청이 취소되었습니다" }
            val printer = request.printer
            val remoteUri = request.remoteUri
            if (remoteUri != null) {
                val name = URI(remoteUri).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "원격 문서"
                setPhase(id, PrintWorkPhase.SENDING)
                IppClient.printRemoteUri(printer, remoteUri, name, request.options)
            } else {
                FileDocumentPreparer(applicationContext).prepare(
                    printer, request.file ?: error("출력할 파일을 선택하세요"), request.settings,
                ).use { prepared ->
                    val options = if (prepared.layoutBakedIn) {
                        PrintLayoutSupport.optionsForLaidOutPages(printer, request.options)
                    } else if (prepared.pageSelectionApplied) request.options.copy(pageRanges = emptyList()) else request.options
                    check(gate.isActive(id)) { "인쇄 요청이 취소되었습니다" }
                    setPhase(id, PrintWorkPhase.SENDING)
                    IppClient.print(printer, prepared.data, prepared.mime, prepared.displayName, options, request.submissionMethod)
                }
            }
        } catch (_: OutOfMemoryError) {
            val sending = state?.phase == PrintWorkPhase.SENDING
            failure(if (sending) "메모리 부족으로 접수 여부를 확인할 수 없습니다. 프린터에서 확인한 뒤 다시 인쇄하세요."
                else "전송 실패: 문서 처리에 필요한 실제 메모리가 부족합니다. 다른 앱을 닫거나 문서를 나누어 다시 시도해 주세요. 원본·해상도·압축 품질은 자동 변경하지 않았습니다.").copy(outcomeUnknown = sending)
        } catch (error: Exception) {
            val sending = state?.phase == PrintWorkPhase.SENDING
            failure(if (sending) "접수 여부를 확인할 수 없습니다. 프린터에서 출력 여부를 확인한 뒤 다시 인쇄하세요. ${error.message.orEmpty()}"
                else error.message ?: "문서를 준비하는 중 알 수 없는 오류가 발생했습니다").copy(outcomeUnknown = sending)
        }
        Thread.interrupted()
        complete(id, result)
    }

    private fun setPhase(id: Long, phase: PrintWorkPhase) {
        synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id || runningId != id) return
            check(cancelRequested != id) { "문서 준비를 취소했습니다" }
            check(record(phase)) { "인쇄 작업 상태를 저장할 수 없습니다" }
            replaceStateLocked(current.copy(phase = phase))
        }
        notifyObserver()
    }

    private fun complete(id: Long, result: PrintResult) {
        synchronized(lock) {
            if (runningId == id) { runningId = null; workerThread = null }
            val current = state
            if (!gate.isActive(id) || current?.requestId != id) return
            val phase = when {
                cancelRequested == id -> PrintWorkPhase.CANCELLED
                result.outcomeUnknown -> PrintWorkPhase.UNKNOWN
                result.accepted -> PrintWorkPhase.ACCEPTED
                result.authRequired -> PrintWorkPhase.AUTH_REQUIRED
                else -> PrintWorkPhase.FAILED
            }
            record(phase)
            if (phase != PrintWorkPhase.AUTH_REQUIRED) gate.finish(id)
            replaceStateLocked(current.copy(phase = phase, result = result,
                checkingStatus = phase == PrintWorkPhase.ACCEPTED))
        }
        notifyObserver()
        if (result.accepted) checkJobStatus(id)
    }

    /** Only reads the latest submitted job. Unknown states never trigger another submission. */
    fun checkJobStatus(id: Long) {
        synchronized(lock) {
            val current = state ?: return
            if (current.requestId != id || current.phase != PrintWorkPhase.ACCEPTED || current.jobStatus?.terminal == true) return
            statusTask?.cancel(false)
            val generation = ++statusGeneration
            replaceStateLocked(current.copy(checkingStatus = true, statusError = null))
            val started = System.nanoTime()
            statusTask = statusWorker.schedule({ pollJob(id, generation, started, 0) }, 0, TimeUnit.SECONDS)
        }
        notifyObserver()
    }

    private fun pollJob(id: Long, generation: Long, started: Long, previousFailures: Int) {
        val current = synchronized(lock) {
            state?.takeIf { it.requestId == id && statusGeneration == generation && it.phase == PrintWorkPhase.ACCEPTED }
        } ?: return
        val result = runCatching {
            IppClient.getJobStatus(current.request.printer.endpoint, current.result?.jobId, current.result?.jobUri)
        }
        synchronized(lock) {
            val latest = state ?: return
            if (latest.requestId != id || statusGeneration != generation || latest.phase != PrintWorkPhase.ACCEPTED) return
            val status = result.getOrNull() ?: latest.jobStatus
            val failures = if (result.isSuccess) 0 else previousFailures + 1
            val timedOut = System.nanoTime() - started >= TimeUnit.MINUTES.toNanos(5)
            val keepChecking = status?.terminal != true && failures < 3 && !timedOut
            val error = when {
                status?.terminal == true || keepChecking -> null
                timedOut -> "상태 확인 시간이 지났습니다. 필요하면 상태를 다시 확인하세요."
                else -> result.exceptionOrNull()?.message ?: "프린터에서 출력 결과를 확인하지 못했습니다"
            }
            replaceStateLocked(latest.copy(jobStatus = status, checkingStatus = keepChecking, statusError = error))
            if (status?.terminal == true) journal?.edit()?.putString("phase", "PRINTER_FINISHED")?.apply()
            if (keepChecking) {
                statusTask = statusWorker.schedule({ pollJob(id, generation, started, failures) }, 2, TimeUnit.SECONDS)
            }
        }
        notifyObserver()
    }

    private fun replaceStateLocked(updated: PrintWorkState) {
        state = updated
        stateVersion += 1
    }

    private fun notifyObserver() {
        main.removeCallbacks(dispatchState)
        main.post(dispatchState)
    }

    private fun failure(message: String) = PrintResult(false, null, null, null, message)

}
