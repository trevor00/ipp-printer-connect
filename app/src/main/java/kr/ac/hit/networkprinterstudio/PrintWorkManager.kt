package kr.ac.hit.networkprinterstudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.util.concurrent.Executors

internal enum class PrintWorkPhase {
    WAITING_NAME, PREPARING, SENDING, AUTH_REQUIRED, ACCEPTED, FAILED, CANCELLED,
}

internal data class PrintWorkState(
    val requestId: Long,
    val request: PrintWorkRequest,
    val phase: PrintWorkPhase,
    val result: PrintResult? = null,
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
    private var runningId: Long? = null
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

    /** Only dismisses a name/authentication prompt; an in-flight printer job is not cancelled. */
    fun cancel(id: Long) {
        synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id || runningId != null ||
                current.phase !in setOf(PrintWorkPhase.WAITING_NAME, PrintWorkPhase.AUTH_REQUIRED)) return
            gate.finish(id)
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
        val result = try {
            check(gate.isActive(id)) { "인쇄 요청이 취소되었습니다" }
            val printer = request.printer
            val remoteUri = request.remoteUri
            if (remoteUri != null) {
                val name = URI(remoteUri).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "원격 문서"
                setPhase(id, PrintWorkPhase.SENDING)
                IppClient.printRemoteUri(printer, remoteUri, name, request.options)
            } else {
                val prepared = FileDocumentPreparer(applicationContext).prepare(
                    printer, request.file ?: error("출력할 파일을 선택하세요"), request.settings,
                )
                require(prepared.bytes.size <= MAX_FILE_BYTES) {
                    "전송 실패: 생성된 문서가 50 MiB 한도를 초과했습니다. 해상도나 압축 설정을 직접 변경하거나 문서를 나누어 주세요. 자동 축소하지 않았습니다."
                }
                val options = if (prepared.layoutBakedIn) {
                    PrintLayoutSupport.optionsForLaidOutPages(printer, request.options)
                } else request.options
                check(gate.isActive(id)) { "인쇄 요청이 취소되었습니다" }
                setPhase(id, PrintWorkPhase.SENDING)
                IppClient.print(printer, prepared.bytes, prepared.mime, prepared.displayName, options, request.submissionMethod)
            }
        } catch (_: OutOfMemoryError) {
            failure("전송 실패: 선택한 품질의 문서를 처리할 메모리가 부족합니다. 해상도·압축 설정을 직접 변경해 주세요. 자동으로 품질을 낮추지 않았습니다.")
        } catch (error: Exception) {
            failure(error.message ?: "문서를 준비하는 중 알 수 없는 오류가 발생했습니다")
        }
        complete(id, result)
    }

    private fun setPhase(id: Long, phase: PrintWorkPhase) {
        synchronized(lock) {
            val current = state
            if (!gate.isActive(id) || current?.requestId != id || runningId != id) return
            replaceStateLocked(current.copy(phase = phase))
        }
        notifyObserver()
    }

    private fun complete(id: Long, result: PrintResult) {
        synchronized(lock) {
            if (runningId == id) runningId = null
            val current = state
            if (!gate.isActive(id) || current?.requestId != id) return
            val phase = when {
                result.accepted -> PrintWorkPhase.ACCEPTED
                result.authRequired -> PrintWorkPhase.AUTH_REQUIRED
                else -> PrintWorkPhase.FAILED
            }
            if (phase != PrintWorkPhase.AUTH_REQUIRED) gate.finish(id)
            replaceStateLocked(current.copy(phase = phase, result = result))
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

    private const val MAX_FILE_BYTES = 50 * 1024 * 1024
}
