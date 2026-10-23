package kr.ac.hit.networkprinterstudio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var discovery: PrinterDiscovery
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var previewGeneration = 0
    private var previewTask: Future<*>? = null
    private var activePreviewDialog: android.app.AlertDialog? = null
    private val printGate get() = PrintWorkManager.gate
    private val printObserverOwner = Any()
    private val filePreparer by lazy { FileDocumentPreparer(applicationContext) }
    private var recreatingUi = false
    private var selectedFileMime: String? = null
    private var documentInspectionGeneration = 0
    private var restoringEditor = false
    private lateinit var pageHandlingLabel: TextView
    private lateinit var mainScroll: ScrollView
    private val expandablePanels = linkedMapOf<String, Pair<View, TextView>>()
    private var activePrintNameInput: EditText? = null
    private var pendingPrinterAlias: String? = null
    private var activePrintDialog: android.app.AlertDialog? = null
    private lateinit var printButton: Button
    private val printers = mutableListOf<PrinterRecord>()
    private val visiblePrinters = mutableListOf<PrinterRecord>()
    private val savedPrinters = mutableListOf<PrinterRecord>()
    private val savedPrinterAliases = mutableMapOf<String, String>()
    private lateinit var adapter: ArrayAdapter<PrinterRecord>
    private var selected: PrinterRecord? = null
    private var selectedFile: Uri? = null
    private var lastBgpScope: BgpScope? = null
    private var scanUiGeneration = 0
    private var mdnsFinishRunnable: Runnable? = null
    private var mdnsCountdownRunnable: Runnable? = null
    private var mdnsSecondsRemaining = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val ink = Color.rgb(24, 32, 46)
    private val muted = Color.rgb(100, 113, 132)
    private val accent = Color.rgb(37, 99, 235)
    private val accentDark = Color.rgb(29, 78, 216)
    private val accentSoft = Color.rgb(239, 246, 255)
    private val canvas = Color.rgb(244, 247, 251)
    private val line = Color.rgb(222, 228, 237)
    private val danger = Color.rgb(190, 18, 60)
    private val dangerSoft = Color.rgb(255, 241, 243)
    private val success = Color.rgb(2, 122, 72)
    private val successSoft = Color.rgb(236, 253, 243)

    private lateinit var status: TextView
    private lateinit var printStatus: TextView
    private lateinit var refreshJobStatusButton: Button
    private lateinit var tcpProgressLabel: TextView
    private lateinit var ippProgressLabel: TextView
    private lateinit var directConnectionStatus: TextView
    private lateinit var rangeSearchStatus: TextView
    private lateinit var networkInfoLabel: TextView
    private lateinit var bgpScopeLabel: TextView
    private lateinit var cidr: EditText
    private lateinit var hostCap: EditText
    private lateinit var directPrinterAddress: EditText
    private lateinit var savedPrintersExpander: TextView
    private lateinit var savedPrintersPanel: LinearLayout
    private lateinit var savedPrintersCards: LinearLayout
    private lateinit var printerSearch: EditText
    private lateinit var printerSearchResult: TextView
    private lateinit var printerList: ListView
    private lateinit var selectedLabel: TextView
    private lateinit var formatsLabel: TextView
    private lateinit var printerAuthButton: Button
    private lateinit var submissionMethodSpinner: Spinner
    private var submissionMethodValues: List<JobSubmissionMethod> = listOf(JobSubmissionMethod.AUTO)
    private lateinit var cancelPrintButton: Button
    private var updatingLayoutControls = false
    private lateinit var colorModeSpinner: Spinner
    private var colorModeValues: List<String?> = listOf(null)
    private lateinit var mediaSpinner: Spinner
    private var mediaValues: List<String?> = listOf(null)
    private lateinit var printScalingSpinner: Spinner
    private var printScalingValues: List<String?> = listOf(null)
    private lateinit var sidesSpinner: Spinner
    private var sidesValues: List<String?> = listOf(null)
    private lateinit var orientationSpinner: Spinner
    private var orientationValues: List<Int?> = listOf(null)
    private lateinit var printQualitySpinner: Spinner
    private var printQualityValues: List<Int?> = listOf(null)
    private lateinit var resolutionSpinner: Spinner
    private var resolutionValues: List<IppResolution?> = listOf(null)
    private lateinit var numberUpSpinner: Spinner
    private var numberUpValues: List<Int?> = listOf(null)
    private lateinit var xPositionSpinner: Spinner
    private var xPositionValues: List<String?> = listOf(null)
    private lateinit var yPositionSpinner: Spinner
    private var yPositionValues: List<String?> = listOf(null)
    private lateinit var copiesInput: EditText
    private lateinit var pageRangesInput: EditText
    private lateinit var contentHorizontalSpinner: Spinner
    private lateinit var contentVerticalSpinner: Spinner
    private lateinit var contentScaleSpinner: Spinner
    private lateinit var contentMarginSpinner: Spinner
    private lateinit var contentMarginDetailLabel: TextView
    private var contentMarginValues: List<Int> = listOf(0, 20, 43, 77)
    private var contentMarginDefaultIndex = 2
    private lateinit var rasterDpiSpinner: Spinner
    private val standardRasterDpiValues: List<Int?> = listOf(null, 75, 100, 150, 200, 300, 600, 1200)
    private var rasterDpiValues: List<Int?> = standardRasterDpiValues
    private lateinit var psEncodingSpinner: Spinner
    private lateinit var conversionPanel: LinearLayout
    private lateinit var conversionFormatSpinner: Spinner
    private lateinit var conversionReasonLabel: TextView
    private lateinit var conversionDescriptionLabel: TextView
    private lateinit var conversionOptionsPanel: LinearLayout
    private lateinit var conversionOptionsHelp: TextView
    private var conversionTargetMimes: List<String?> = listOf(null)
    private var updatingConversionTargets = false
    private var lastConversionMime: String? = null
    private lateinit var contentScalePercentInput: EditText
    private lateinit var contentOffsetXInput: EditText
    private lateinit var contentOffsetYInput: EditText
    private lateinit var remoteDocumentUri: EditText
    private lateinit var fileLabel: TextView
    private lateinit var chooseFileButton: Button
    private lateinit var clearFileButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        runCatching { PrintCache.initialize(applicationContext) }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        PrintWorkManager.initialize(applicationContext)
        discovery = PrinterDiscovery(this)
        requestNearbyPermission()
        loadSavedPrinters()
        buildUi()
        val network = discovery.currentNetwork()
        cidr.setText(network?.connectedCidr.orEmpty())
        refreshNetworkInfo()
        val restoredEgress = savedInstanceState?.getString(STATE_BGP_EGRESS)
        val restoredPrefix = savedInstanceState?.getString(STATE_BGP_PREFIX)
        if (!restoredEgress.isNullOrBlank() && !restoredPrefix.isNullOrBlank()) {
            displayBgpScope(BgpScope(restoredEgress, restoredPrefix))
        }
        restoreEditorState(savedInstanceState?.getBundle(STATE_EDITOR) ?: retainedEditorState)
        if (savedInstanceState?.getBoolean(STATE_PRINT_WAS_ACTIVE) == true && PrintWorkManager.state == null) {
            updateStatus("앱 종료 전 인쇄 요청의 결과를 확인할 수 없습니다. 프린터에서 출력 여부를 확인한 뒤 다시 인쇄하세요.", StatusTone.NEUTRAL)
        }
        PrintWorkManager.interruptedWorkMessage?.let { updateStatus(it, StatusTone.ERROR) }
        startNetworkMonitoring()
    }

    override fun onStart() {
        super.onStart()
        PrintWorkManager.observe(printObserverOwner, ::renderPrintWork)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            // Keep scrolled content outside the status/navigation-bar safe areas.
            clipToPadding = true
            setBackgroundColor(canvas)
            isFocusableInTouchMode = true
            requestFocus()
        }
        installAdaptiveInsets(scroll)
        mainScroll = scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(28))
        }
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(-1, -2))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(19))
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "IPP Printer Connect"
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val heroCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        heroCopy.addView(TextView(this).apply {
            text = "프린터"
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
            includeFontPadding = false
        })
        heroCopy.addView(TextView(this).apply {
            text = "문서를 고르고 바로 인쇄하세요"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
            includeFontPadding = false
        })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(badge("IPP", accentSoft, accent))
        root.addView(hero)

        val networkCard = section("프린터 연결", "", "IP 주소를 몰라도 현재 Wi-Fi에서 프린터를 찾을 수 있습니다")
        directPrinterAddress = EditText(this).apply {
            hint = "예: 192.168.0.20"
            setSingleLine()
            styleInput()
        }
        networkCard.addView(button("현재 Wi-Fi에서 프린터 찾기", primary = true) { startMdns() }, LinearLayout.LayoutParams(-1, -2))
        networkCard.addView(TextView(this).apply {
            text = "같은 Wi-Fi에 연결된 호환 네트워크 프린터를 자동으로 찾습니다."
            textSize = 12.5f
            setTextColor(muted)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        })

        savedPrintersPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        savedPrintersCards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        savedPrintersPanel.addView(savedPrintersCards)
        savedPrintersPanel.addView(
            dangerButton("저장된 프린터 모두 지우기") { clearAllSavedPrinters() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        savedPrintersExpander = expander("저장된 프린터", savedPrintersPanel).apply {
            setOnClickListener {
                savedPrintersPanel.visibility =
                    if (savedPrintersPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateSavedPrintersExpander()
            }
        }
        networkCard.addView(savedPrintersExpander, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        networkCard.addView(savedPrintersPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        refreshSavedPrinterCards()
        status = TextView(this).apply {
            text = "준비"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(success)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(successSoft, successSoft, dp(10).toFloat())
        }
        tcpProgressLabel = progressLabel()
        ippProgressLabel = progressLabel()
        networkCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        val networkAdvanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        networkAdvanced.addView(subsectionTitle("IP 주소로 직접 연결"))
        networkAdvanced.addView(fieldLabel("프린터 IP 또는 IPP 주소"))
        networkAdvanced.addView(directPrinterAddress, LinearLayout.LayoutParams(-1, dp(52)))
        networkAdvanced.addView(button("IP 주소로 연결", primary = true) { connectDirectPrinter() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        directConnectionStatus = inlineStatusView()
        networkAdvanced.addView(directConnectionStatus, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        networkAdvanced.addView(subsectionTitle("네트워크 범위 검색"))
        networkInfoLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(249, 250, 251), line, dp(10).toFloat())
        }
        bgpScopeLabel = TextView(this).apply {
            text = "확인된 BGP 대역 없음"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(249, 250, 251), line, dp(10).toFloat())
        }
        val scopeLabels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scopeLabels.addView(TextView(this).apply {
            text = "검색할 IP 범위 (CIDR)"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(0, -2, 3f))
        scopeLabels.addView(TextView(this).apply {
            text = "최대 검사 수"
            textSize = 12f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        networkAdvanced.addView(scopeLabels)
        val scopeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cidr = EditText(this).apply { hint = "192.168.0.0/24"; setSingleLine(); styleInput() }
        hostCap = EditText(this).apply { hint = "254"; setText("4096"); inputType = 2; setSingleLine(); styleInput() }
        scopeRow.addView(cidr, LinearLayout.LayoutParams(0, dp(52), 3f))
        scopeRow.addView(hostCap, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        networkAdvanced.addView(scopeRow)
        rangeSearchStatus = inlineStatusView()
        networkAdvanced.addView(rangeSearchStatus, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        networkAdvanced.addView(tcpProgressLabel)
        networkAdvanced.addView(ippProgressLabel)

        networkAdvanced.addView(button("Wi-Fi가 알려준 서브넷 범위 설정") { loadWifiAdvertisedSearchRange() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        networkAdvanced.addView(button("현재 휴대폰 IP 기준 /24 범위 설정") { loadWifi24SearchRange() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })

        val cidrButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cidrButtons.addView(button("빠르게 검색", primary = true) { startCidr(100) }, LinearLayout.LayoutParams(0, -2, 1f))
        cidrButtons.addView(button("꼼꼼하게 검색", primary = true) { startCidr(1_200) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        networkAdvanced.addView(cidrButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        networkAdvanced.addView(dangerButton("검색 중지") { stopPrinterSearch() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        networkAdvanced.addView(subsectionTitle("네트워크 정보"))
        networkAdvanced.addView(networkInfoLabel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
        })
        networkAdvanced.addView(subsectionTitle("네트워크 연구 도구"))
        networkAdvanced.addView(bgpScopeLabel, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        val externalButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        externalButtons.addView(button("인터넷 IP 대역 조회") { lookupBgp() }, LinearLayout.LayoutParams(0, -2, 1f))
        externalButtons.addView(button("인터넷 IP /24 탐색") { scanCurrentEgress24() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        networkAdvanced.addView(externalButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        networkCard.addView(expander("추가 연결 방법", networkAdvanced), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        networkCard.addView(networkAdvanced)
        addSection(root, networkCard)

        val printersCard = section("프린터 선택", "", "인쇄할 프린터를 하나 선택하세요")
        printerSearch = EditText(this).apply {
            hint = "이름, IP, 모델, URI 검색"
            setSingleLine()
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            styleInput()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = applyPrinterFilter()
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }
        printersCard.addView(printerSearch, LinearLayout.LayoutParams(-1, dp(48)))
        printerSearchResult = TextView(this).apply {
            text = "발견된 프린터 없음"
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(9), dp(2), dp(9))
        }
        printersCard.addView(printerSearchResult)
        adapter = object : ArrayAdapter<PrinterRecord>(this, android.R.layout.simple_list_item_activated_1, visiblePrinters) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val printer = getItem(position) ?: return View(this@MainActivity)
                return printerItemView(printer)
            }
        }
        printerList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = this@MainActivity.adapter
            isNestedScrollingEnabled = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        // A gesture that starts inside the printer list always belongs
                        // to the list, even when it reaches its first or last row.
                        scroll.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        scroll.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            setPadding(0, dp(2), 0, dp(2))
            clipToPadding = false
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                selectPrinter(visiblePrinters[position])
            }
        }
        printersCard.addView(printerList, LinearLayout.LayoutParams(-1, dp(190)))
        printersCard.addView(button("검색 결과 지우기") { clearPrinterList() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addSection(root, printersCard)

        val detailCard = section("인쇄 설정", "", "선택한 프린터에 맞는 옵션만 표시합니다")
        selectedLabel = TextView(this).apply {
            text = "프린터를 선택하세요"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = roundedBackground(Color.rgb(249, 250, 251), line, dp(10).toFloat())
            setTextIsSelectable(true)
        }
        formatsLabel = TextView(this).apply {
            text = "지원 출력 형식이 여기에 표시됩니다"
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(2), dp(10), dp(2), dp(8))
            setTextIsSelectable(true)
        }
        detailCard.addView(selectedLabel)
        val printerTechnicalDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        printerTechnicalDetails.addView(formatsLabel)
        printerAuthButton = button("프린터 로그인 / 자격 증명") {
            selected?.let(::showPrinterAuthDialog)
                ?: Toast.makeText(this, "프린터를 먼저 선택하세요", Toast.LENGTH_SHORT).show()
        }.apply {
            visibility = View.GONE
        }
        detailCard.addView(
            printerAuthButton,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        detailCard.addView(expander("지원 형식 및 연결 정보", printerTechnicalDetails))
        detailCard.addView(printerTechnicalDetails)
        colorModeSpinner = addSpinnerRow(detailCard, "색상 모드")
        mediaSpinner = addSpinnerRow(detailCard, "용지 크기")
        printScalingSpinner = addSpinnerRow(detailCard, "내용 크기 맞춤")
        val advancedPrintSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        submissionMethodSpinner = addSpinnerRow(advancedPrintSettings, "IPP 출력 방식")
        sidesSpinner = addSpinnerRow(advancedPrintSettings, "단면/양면")
        orientationSpinner = addSpinnerRow(advancedPrintSettings, "출력 방향")
        printQualitySpinner = addSpinnerRow(advancedPrintSettings, "인쇄 품질")
        resolutionSpinner = addSpinnerRow(advancedPrintSettings, "프린터 인쇄 해상도")
        numberUpSpinner = addSpinnerRow(advancedPrintSettings, "한 장당 페이지")
        xPositionSpinner = addSpinnerRow(advancedPrintSettings, "가로 위치")
        yPositionSpinner = addSpinnerRow(advancedPrintSettings, "세로 위치")
        copiesInput = addTextOptionRow(advancedPrintSettings, "복사 매수", "1").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        pageRangesInput = addTextOptionRow(advancedPrintSettings, "페이지 범위", "예: 1-3,5")
        pageHandlingLabel = TextView(this).apply { textSize = 12f; setTextColor(muted); setPadding(dp(2), dp(5), dp(2), dp(8)) }
        advancedPrintSettings.addView(pageHandlingLabel)
        setSubmissionMethodOptions(null)
        setColorModeOptions(null)
        setMediaOptions(null)
        setPrintScalingOptions(null)
        setExtendedPrintOptions(null)
        val documentCard = section("문서", "", "프린터 지원 형식은 원본으로 전송합니다")
        chooseFileButton = button("사진 또는 문서 선택", primary = true) { chooseFile() }
        documentCard.addView(chooseFileButton)
        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = roundedBackground(Color.rgb(248, 250, 252), line, dp(11).toFloat())
        }
        fileLabel = TextView(this).apply {
            text = "선택한 파일이 없습니다"
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fileRow.addView(fileLabel, LinearLayout.LayoutParams(0, -2, 1f))
        clearFileButton = button("지우기") {
            cancelPendingPreview()
            selectedFile = null
            selectedFileMime = null
            conversionTargetMimes = listOf(null)
            lastConversionMime = null
            documentInspectionGeneration += 1
            fileLabel.text = "선택한 파일이 없습니다"
            updateDocumentInputAvailability()
        }
        fileRow.addView(clearFileButton)
        documentCard.addView(fileRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(16) })
        conversionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.rgb(247, 248, 255), line, dp(11).toFloat())
            visibility = View.GONE
        }
        conversionReasonLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }
        conversionPanel.addView(conversionReasonLabel)
        conversionFormatSpinner = addFixedSpinnerRow(conversionPanel, "전송 형식", listOf("자동 선택"), 0)
        conversionDescriptionLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(3), dp(2), dp(5))
            visibility = View.GONE
        }
        conversionPanel.addView(conversionDescriptionLabel)
        conversionOptionsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        rasterDpiSpinner = addFixedSpinnerRow(
            conversionOptionsPanel,
            "변환 해상도",
            listOf("자동", "75 dpi", "100 dpi", "150 dpi", "200 dpi", "300 dpi", "600 dpi", "1200 dpi"),
            0,
        )
        psEncodingSpinner = addFixedSpinnerRow(
            conversionOptionsPanel,
            "색상 데이터 압축",
            listOf("무손실 RGB", "JPEG 품질 100 (손실)", "JPEG 품질 90 (손실)"),
            0,
        )
        conversionOptionsHelp = TextView(this).apply {
            text = "자동은 보통 300dpi를 사용하고, PWG Raster·URF는 프린터 광고 해상도에 맞춥니다. 직접 고른 값은 자동으로 낮추지 않습니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(2), dp(2), dp(5))
        }
        conversionOptionsPanel.addView(conversionOptionsHelp)
        conversionPanel.addView(conversionOptionsPanel)
        documentCard.addView(conversionPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(12) })
        documentCard.addView(fieldLabel("또는 프린터가 직접 가져올 문서 URL"))
        remoteDocumentUri = EditText(this).apply {
            hint = "문서 URL (프린터가 직접 다운로드)"
            setSingleLine()
            styleInput()
        }
        documentCard.addView(TextView(this).apply {
            text = "Print-URI/Send-URI 지원 프린터에 URL만 전달합니다. 프린터가 원본을 직접 받으므로 앱 변환과 페이지 배치는 적용되지 않습니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        documentCard.addView(remoteDocumentUri, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        val documentInputWatcher = object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) =
                updateDocumentInputAvailability()
            override fun afterTextChanged(value: Editable?) = Unit
        }
        remoteDocumentUri.addTextChangedListener(documentInputWatcher)
        updateDocumentInputAvailability()
        val layoutSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        contentHorizontalSpinner = addFixedSpinnerRow(layoutSettings, "가로 배치", listOf("왼쪽", "가운데", "오른쪽"), 1)
        contentVerticalSpinner = addFixedSpinnerRow(layoutSettings, "세로 배치", listOf("위", "가운데", "아래"), 1)
        contentScaleSpinner = addFixedSpinnerRow(
            layoutSettings,
            "이미지 크기",
            listOf(
                "원본 유지 (변환 시 용지에 맞춤)",
                "페이지에 맞춤",
                "페이지 채우기",
                "페이지 전부 채우기 (넘치는 부분 잘림)",
                "페이지 채우기 (프린트 불가 영역 제외)",
                "원본 픽셀 크기",
                "큰 내용만 축소",
            ),
            0,
        )
        layoutSettings.addView(TextView(this).apply {
            text = "페이지에 맞춤: 전체 내용이 보이도록 설정한 내부 여백 안에 맞춥니다.\n" +
                "페이지 채우기: 설정한 내부 여백 안쪽을 채우며 넘친 부분은 잘릴 수 있습니다.\n" +
                "페이지 전부 채우기: 비율을 유지한 채 선택한 용지 전체를 덮고 넘친 부분을 자릅니다.\n" +
                "프린트 불가 영역 제외: 전체 내용이 출력 가능 영역 안에 들어오도록 맞춰 잘리지 않게 합니다.\n" +
                "원본 픽셀 크기: 확대·축소 없이 원본 픽셀 크기로 배치합니다.\n" +
                "큰 내용만 축소: 용지보다 큰 내용은 줄이고 작은 내용은 확대하지 않습니다.\n" +
                "※ 출력 가능 영역 정보가 없는 프린터에서는 해당 영역에 맞춤을 사용할 수 없습니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })
        contentMarginSpinner = addFixedSpinnerRow(
            layoutSettings,
            "문서 내부 여백",
            listOf("없음 (0mm)", "좁게 (약 7mm)", "보통 (약 15mm)", "넓게 (약 27mm)"),
            2,
        )
        contentMarginDetailLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(10), dp(2), dp(10), dp(8))
            visibility = View.GONE
        }
        layoutSettings.addView(contentMarginDetailLabel)
        setContentMarginOptions(null)
        contentScalePercentInput = addTextOptionRow(layoutSettings, "내용 크기 (%)", "10~400").apply {
            setText("100")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        contentOffsetXInput = addTextOptionRow(layoutSettings, "가로 이동 (mm)", "-300~300").apply {
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        contentOffsetYInput = addTextOptionRow(layoutSettings, "세로 이동 (mm)", "-300~300").apply {
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val finalActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        finalActions.addView(button("미리보기") { showPrintPreview() }, LinearLayout.LayoutParams(0, -2, 1f))
        printButton = button("인쇄하기", primary = true) { validateAndPrint() }
        finalActions.addView(printButton, LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginStart = dp(8) })

        addSection(root, documentCard)

        detailCard.addView(
            expander("고급 IPP 프린트 설정 보기", advancedPrintSettings),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        detailCard.addView(advancedPrintSettings)
        detailCard.addView(
            expander("페이지 배치", layoutSettings),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        detailCard.addView(layoutSettings)
        detailCard.addView(
            finalActions,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
        )
        cancelPrintButton = button("문서 준비 취소") { PrintWorkManager.state?.let { PrintWorkManager.cancel(it.requestId) } }.apply { visibility = View.GONE }
        detailCard.addView(
            cancelPrintButton,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        printStatus = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            visibility = View.GONE
        }
        detailCard.addView(printStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        refreshJobStatusButton = button("인쇄 상태 다시 확인") {
            PrintWorkManager.state?.let { PrintWorkManager.checkJobStatus(it.requestId) }
        }.apply { visibility = View.GONE }
        detailCard.addView(refreshJobStatusButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detailCard.addView(TextView(this).apply {
            text = "인쇄 결과를 확인할 때까지 앱을 열어 두세요. 상태를 조회할 수 없는 프린터는 접수 여부까지만 표시됩니다."
            textSize = 12f; setTextColor(muted); setPadding(dp(2), dp(8), dp(2), 0)
        })
        addSection(root, detailCard)

        installConversionOptionListeners()
        applyAppTypeface(root)

        setContentView(scroll)
        scroll.post { scroll.requestFocus() }
        applySystemBarIconContrast(scroll)
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarIconContrast(root: View) {
        root.post {
            if (Build.VERSION.SDK_INT >= 30) {
                root.windowInsetsController?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            } else {
                var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 27) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                root.systemUiVisibility = flags
            }
        }
    }

    private fun installAdaptiveInsets(root: View) {
        val horizontal = dp(16)
        val vertical = dp(12)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(
                    horizontal + safe.left,
                    vertical + safe.top,
                    horizontal + safe.right,
                    vertical + maxOf(safe.bottom, keyboard.bottom),
                )
                insets
            }
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            root.requestApplyInsets()
        } else {
            // Android 10 이하에서는 decor가 시스템 바 영역을 이미 제외합니다.
            root.setPadding(horizontal, vertical, horizontal, vertical)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun applyAppTypeface(view: View) {
        if (view is TextView) {
            val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
            val family = if (style == Typeface.BOLD) "sans-serif-medium" else "sans-serif"
            view.typeface = Typeface.create(family, Typeface.NORMAL)
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) applyAppTypeface(view.getChildAt(index))
        }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = radius
    }

    private fun rippleBackground(fill: Int, stroke: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(28, 15, 23, 42)),
        roundedBackground(fill, stroke, dp(radius).toFloat()),
        roundedBackground(Color.WHITE, Color.WHITE, dp(radius).toFloat()),
    )

    private fun section(title: String, number: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(Color.WHITE, Color.rgb(232, 236, 243), dp(16).toFloat())
        elevation = dp(1).toFloat()
        val heading = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val copy = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
            includeFontPadding = false
        })
        copy.addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
            includeFontPadding = false
        })
        heading.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })
    }

    private fun addSection(root: LinearLayout, section: View) {
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(50)
        minWidth = 0
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else accentDark)
        background = rippleBackground(if (primary) accent else accentSoft, if (primary) accent else accentSoft, 12)
        stateListAnimator = null
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }

    private fun dangerButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(48)
        minWidth = 0
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(danger)
        background = rippleBackground(dangerSoft, dangerSoft, 12)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun badge(label: String, fill: Int, foreground: Int) = TextView(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(foreground)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = roundedBackground(fill, fill, dp(20).toFloat())
    }

    private fun fieldLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(muted)
        setPadding(0, 0, 0, dp(6))
    }

    private fun subsectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        setPadding(0, dp(19), 0, dp(8))
    }

    private fun EditText.styleInput() {
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(Color.rgb(152, 162, 179))
        background = roundedBackground(Color.rgb(249, 250, 251), line, dp(10).toFloat())
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun expander(label: String, target: View) = TextView(this).apply {
        expandablePanels[label] = target to this
        text = "$label  ＋"
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accentDark)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = rippleBackground(accentSoft, accentSoft, 10)
        setOnClickListener {
            val expanded = target.visibility == View.VISIBLE
            target.visibility = if (expanded) View.GONE else View.VISIBLE
            text = "$label  ${if (expanded) "＋" else "−"}"
        }
    }

    private fun printerItemView(
        printer: PrinterRecord,
        onSelect: () -> Unit = { selectPrinter(printer) },
        onDelete: (() -> Unit)? = null,
        onEditName: (() -> Unit)? = null,
    ): View {
        val isSelected = selected?.endpoint?.key == printer.endpoint.key
        val authenticationRequired = !printer.available && printer.authChallenge != null
        val itemFill = when {
            authenticationRequired -> Color.rgb(255, 250, 235)
            !printer.available -> dangerSoft
            isSelected -> accentSoft
            else -> Color.rgb(249, 250, 251)
        }
        val itemStroke = when {
            authenticationRequired -> Color.rgb(245, 158, 11)
            !printer.available -> Color.rgb(254, 205, 211)
            isSelected -> accent
            else -> line
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(itemFill, itemStroke, dp(13).toFloat()).also {
                if (isSelected) it.setStroke(dp(2), itemStroke)
            }
            setOnClickListener { onSelect() }
            setOnLongClickListener {
                copyPrinterDetails(printer)
                true
            }
            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this@MainActivity).apply {
                text = printer.name.ifBlank { printer.endpoint.host }
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(
                    when {
                        authenticationRequired -> Color.rgb(146, 64, 14)
                        printer.available -> ink
                        else -> danger
                    }
                )
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (onEditName != null) {
                titleRow.addView(
                    badge("이름", accentSoft, accentDark).apply {
                        contentDescription = "${printer.name.ifBlank { printer.endpoint.host }} 저장 이름 편집"
                        isClickable = true
                        isFocusable = true
                        background = rippleBackground(accentSoft, accentSoft, 20)
                        setOnClickListener { onEditName() }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                )
            }
            if (onDelete != null) {
                titleRow.addView(
                    badge("삭제", dangerSoft, danger).apply {
                        contentDescription = "${printer.name.ifBlank { printer.endpoint.host }} 저장 목록에서 삭제"
                        isClickable = true
                        isFocusable = true
                        background = rippleBackground(dangerSoft, dangerSoft, 20)
                        setOnClickListener { onDelete() }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                )
            }
            titleRow.addView(
                badge("복사", accentSoft, accentDark).apply {
                    contentDescription = "${printer.name.ifBlank { printer.endpoint.host }} 정보 복사"
                    isClickable = true
                    isFocusable = true
                    background = rippleBackground(accentSoft, accentSoft, 20)
                    setOnClickListener { copyPrinterDetails(printer) }
                },
                LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(6)
                },
            )
            titleRow.addView(
                badge(
                    when {
                        authenticationRequired -> "인증 필요"
                        printer.available -> "IPP ${printer.endpoint.ippVersion}"
                        else -> "연결 불가"
                    },
                    when {
                        authenticationRequired -> Color.rgb(254, 243, 199)
                        printer.available -> successSoft
                        else -> Color.rgb(255, 228, 230)
                    },
                    when {
                        authenticationRequired -> Color.rgb(146, 64, 14)
                        printer.available -> success
                        else -> danger
                    },
                ),
            )
            addView(titleRow)
            if (printer.model.isNotBlank() && printer.model != printer.name) {
                addView(TextView(this@MainActivity).apply {
                    text = printer.model
                    textSize = 12.5f
                    setTextColor(muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                    setTextIsSelectable(true)
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = printer.endpoint.uri
                textSize = 12f
                setTextColor(if (printer.available) accentDark else muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(5), 0, 0)
                setTextIsSelectable(true)
            })
            if (!printer.available && !printer.statusDetail.isNullOrBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = printer.statusDetail
                    textSize = 12f
                    setTextColor(danger)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                    setTextIsSelectable(true)
                })
            }
        }
    }

    private fun progressLabel() = TextView(this).apply {
        textSize = 13f
        setTextColor(muted)
        visibility = View.GONE
        setPadding(dp(2), dp(5), dp(2), 0)
    }

    private fun inlineStatusView() = TextView(this).apply {
        textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        visibility = View.GONE
    }

    private enum class StatusTone { NEUTRAL, ACTIVE, SUCCESS, ERROR }

    private fun applyStatus(view: TextView, message: String, tone: StatusTone) {
        val (foreground, fill, stroke) = when (tone) {
            StatusTone.NEUTRAL -> Triple(ink, Color.rgb(249, 250, 251), line)
            StatusTone.ACTIVE -> Triple(
                Color.rgb(146, 64, 14),
                Color.rgb(255, 247, 237),
                Color.rgb(253, 186, 116),
            )
            StatusTone.SUCCESS -> Triple(success, successSoft, Color.rgb(167, 243, 208))
            StatusTone.ERROR -> Triple(danger, dangerSoft, Color.rgb(254, 205, 211))
        }
        view.text = message
        view.setTextColor(foreground)
        view.background = roundedBackground(fill, stroke, dp(10).toFloat())
        view.visibility = View.VISIBLE
    }

    private fun updateStatus(message: String, tone: StatusTone = StatusTone.NEUTRAL) =
        applyStatus(status, message, tone)

    private fun updateDirectConnectionStatus(message: String, tone: StatusTone) =
        applyStatus(directConnectionStatus, message, tone)

    private fun updateRangeSearchStatus(message: String, tone: StatusTone) =
        applyStatus(rangeSearchStatus, message, tone)

    private fun addSpinnerRow(parent: LinearLayout, title: String): Spinner {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(3))
        })
        val spinner = Spinner(this).apply {
            minimumHeight = dp(58)
            setPadding(dp(6), 0, dp(6), 0)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(152, 162, 179))
        }
        row.addView(spinner, LinearLayout.LayoutParams(-1, dp(58)))
        parent.addView(row)
        return spinner
    }

    private fun addFixedSpinnerRow(
        parent: LinearLayout,
        title: String,
        labels: List<String>,
        selectedIndex: Int,
    ): Spinner = addSpinnerRow(parent, title).also { spinner ->
        spinner.adapter = scrollableSpinnerAdapter(labels)
        spinner.setSelection(selectedIndex)
    }

    private fun scrollableSpinnerAdapter(labels: List<String>) = object : BaseAdapter(), SpinnerAdapter {
        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): String = labels[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            TextView(this@MainActivity).apply {
                text = labels[position]
                textSize = 13.5f
                setTextColor(ink)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                includeFontPadding = false
                setPadding(dp(10), dp(4), dp(34), dp(4))
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            TextView(this@MainActivity).apply {
                text = labels[position]
                textSize = 14f
                setTextColor(ink)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 3
                minimumHeight = dp(48)
                setPadding(dp(16), dp(12), dp(28), dp(12))
            }
    }

    private fun addTextOptionRow(parent: LinearLayout, title: String, placeholder: String): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(3))
        })
        val input = EditText(this).apply {
            hint = placeholder
            setSingleLine()
            styleInput()
        }
        row.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        parent.addView(row)
        return input
    }

    private fun hideCidrProgress() {
        if (::tcpProgressLabel.isInitialized) tcpProgressLabel.visibility = View.GONE
        if (::ippProgressLabel.isInitialized) ippProgressLabel.visibility = View.GONE
    }

    private fun addPrinter(record: PrinterRecord) = main.post {
        val changed = upsertPrinter(record)
        if (changed && mdnsCountdownRunnable != null) updateMdnsCountdownStatus()
    }

    private fun upsertPrinter(record: PrinterRecord): Boolean {
        val index = printers.indexOfFirst { it.endpoint.key == record.endpoint.key }
        when {
            index < 0 -> printers.add(record)
            !printers[index].available && record.available -> printers[index] = record
            !printers[index].available && !record.available &&
                printers[index].authChallenge == null && record.authChallenge != null ->
                printers[index] = record
            printers[index].available == record.available && printers[index].name.isBlank() && record.name.isNotBlank() ->
                printers[index] = record
            else -> return false
        }
        applyPrinterFilter()
        return true
    }

    private fun selectPrinter(printer: PrinterRecord) {
        cancelPendingPreview()
        val stoppedAutomaticSearch = mdnsFinishRunnable != null
        if (stoppedAutomaticSearch) cancelActiveSearch()
        if (!printer.available && printer.authChallenge != null) {
            selected = null
            showSelectedPrinter(printer)
            printerList.clearChoices()
            adapter.notifyDataSetChanged()
            refreshSavedPrinterCards()
            showPrinterAuthDialog(printer)
            return
        }
        selected = printer.takeIf { it.available }
        selectedFile = null
        selectedFileMime = null
        documentInspectionGeneration += 1
        fileLabel.text = "선택한 파일이 없습니다"
        showSelectedPrinter(printer)
        val selectedIndex = visiblePrinters.indexOfFirst {
            it.endpoint.key == printer.endpoint.key
        }
        if (printer.available && selectedIndex >= 0) {
            printerList.setItemChecked(selectedIndex, true)
        } else {
            printerList.clearChoices()
        }
        adapter.notifyDataSetChanged()
        refreshSavedPrinterCards()
        if (stoppedAutomaticSearch && printer.available) {
            updateStatus("프린터를 선택하여 검색을 종료했습니다: ${printer.name.ifBlank { printer.endpoint.host }}", StatusTone.SUCCESS)
        }
    }

    private fun copyPrinterDetails(printer: PrinterRecord) {
        val details = buildString {
            append("이름: ").append(
                printer.name.ifBlank { printer.endpoint.host }
            )
            append("\nIP: ").append(printer.endpoint.host)
            append("\nURI: ").append(printer.endpoint.uri)
            append("\nIPP 버전: ").append(printer.endpoint.ippVersion)
            if (printer.model.isNotBlank()) {
                append("\n모델: ").append(printer.model)
            }
            if (printer.location.isNotBlank()) {
                append("\n위치: ").append(printer.location)
            }
            append("\n상태: ").append(
                if (printer.available) "연결 가능" else "연결 불가"
            )
            if (!printer.statusDetail.isNullOrBlank()) {
                append("\n상세: ").append(printer.statusDetail)
            }
            printer.authChallenge?.let {
                append("\n인증 요구: ").append(it.label)
            }
            if (printer.uriAuthentication.isNotEmpty()) {
                append("\n광고된 인증: ").append(printer.uriAuthentication.joinToString(", "))
            }
            if (printer.uriSecurity.isNotEmpty()) {
                append("\n광고된 보안: ").append(printer.uriSecurity.joinToString(", "))
            }
            if (printer.formats.isNotEmpty()) {
                append("\n지원 형식: ").append(
                    printer.formats.joinToString(", ")
                )
            }
            if (printer.supportedSubmissionMethods.isNotEmpty()) {
                append("\n출력 방식: ").append(
                    printer.supportedSubmissionMethods.joinToString(", ")
                )
            }
            append("\n발견 방식: ").append(printer.source)
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("IPP 프린터 정보", details)
        )
        Toast.makeText(
            this,
            "프린터 정보를 복사했습니다",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun applyPrinterFilter() {
        if (!::adapter.isInitialized || !::printerSearch.isInitialized) return
        val query = printerSearch.text.toString().trim().lowercase()
        visiblePrinters.clear()
        visiblePrinters.addAll(printers.filter { printer -> printer.matches(query) })
        adapter.notifyDataSetChanged()

        printerSearchResult.text = when {
            printers.isEmpty() -> "발견된 프린터 없음"
            query.isEmpty() -> "총 ${printers.size}대"
            visiblePrinters.isEmpty() -> "검색 결과 없음 · 전체 ${printers.size}대"
            else -> "검색 결과 ${visiblePrinters.size}대 · 전체 ${printers.size}대"
        }

        if (::printerList.isInitialized) {
            printerList.clearChoices()
            selected?.let { chosen ->
                val selectedIndex = visiblePrinters.indexOfFirst { it.endpoint.key == chosen.endpoint.key }
                if (selectedIndex >= 0) printerList.setItemChecked(selectedIndex, true)
            }
        }
    }

    private fun PrinterRecord.matches(query: String): Boolean {
        if (query.isEmpty()) return true
        return buildString {
            append(name).append('\n')
            append(model).append('\n')
            append(location).append('\n')
            append(endpoint.host).append(':').append(endpoint.port).append('\n')
            append(endpoint.uri).append('\n')
            append("ipp ").append(endpoint.ippVersion).append('\n')
            append(source).append('\n')
            append(statusDetail.orEmpty()).append('\n')
            append(formats.joinToString(" ")).append('\n')
            append(if (available) "연결 가능 available" else "연결 불가 unavailable")
        }.lowercase().contains(query)
    }

    private fun availablePrinterCount() = printers.count { it.available }
    private fun unavailablePrinterCount() = printers.count { !it.available }

    private fun loadWifiAdvertisedSearchRange() {
        val wifi = currentWifiForRange() ?: return
        val scope = PrinterDiscovery.normalizeCidr(wifi.connectedCidr)
        applySearchRange(
            scope,
            "Wi-Fi가 알려준 서브넷 범위"
        )
    }

    private fun loadWifi24SearchRange() {
        val wifi = currentWifiForRange() ?: return
        val scope = PrinterDiscovery.containing24(wifi.localIp)
        applySearchRange(
            scope,
            "현재 휴대폰 주소 기준 /24 범위"
        )
    }

    private fun currentWifiForRange(): NetworkSnapshot? {
        val wifi = discovery.currentWifiNetwork()
        if (wifi == null) {
            updateRangeSearchStatus("Wi-Fi IPv4 연결을 찾지 못했습니다.", StatusTone.ERROR)
            Toast.makeText(this, "Wi-Fi에 연결한 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
            return null
        }
        return wifi
    }

    private fun applySearchRange(scope: String, source: String) {
        val count = PrinterDiscovery.usableHostCount(scope)
        cidr.setText(scope)
        hostCap.setText(count.coerceAtMost(Int.MAX_VALUE.toLong()).toString())
        updateRangeSearchStatus(
            "$source $scope · ${"%,d".format(count)}대가 입력되었습니다",
            StatusTone.SUCCESS
        )
    }

    private fun startMdns() {
        dismissKeyboard()
        cancelActiveSearch()
        val generation = scanUiGeneration
        hideCidrProgress()
        mdnsSecondsRemaining = 15
        val countdown = object : Runnable {
            override fun run() {
                if (generation != scanUiGeneration || mdnsSecondsRemaining <= 0) return
                updateMdnsCountdownStatus()
                mdnsSecondsRemaining -= 1
                if (mdnsSecondsRemaining > 0) main.postDelayed(this, 1_000)
            }
        }
        mdnsCountdownRunnable = countdown
        main.post(countdown)
        discovery.startMdns(::addPrinter) { message -> main.post {
            if (generation == scanUiGeneration && message.contains("실패")) {
                updateStatus(message, StatusTone.ERROR)
            }
        } }
        val finish = Runnable {
            if (generation != scanUiGeneration) return@Runnable
            discovery.stopMdns()
            mdnsCountdownRunnable?.let(main::removeCallbacks)
            mdnsCountdownRunnable = null
            mdnsSecondsRemaining = 0
            if (availablePrinterCount() > 0) {
                updateStatus("프린터 검색 완료: 연결 가능한 프린터 ${availablePrinterCount()}대", StatusTone.SUCCESS)
            } else {
                updateStatus(
                    "프린터를 찾지 못했습니다. 휴대폰과 프린터가 같은 Wi-Fi에 연결되어 있는지 확인하거나 ‘추가 연결 방법’을 이용해 주세요.",
                    StatusTone.ERROR,
                )
            }
            mdnsFinishRunnable = null
        }
        mdnsFinishRunnable = finish
        main.postDelayed(finish, 15_000)
    }

    private fun updateMdnsCountdownStatus() {
        val found = availablePrinterCount()
        val foundText = if (found > 0) " · 발견 ${found}대" else ""
        updateStatus(
            "현재 Wi-Fi에서 프린터를 찾는 중… 남은 시간 ${mdnsSecondsRemaining}초$foundText",
            StatusTone.ACTIVE,
        )
    }

    private fun startCidr(timeoutMs: Int) {
        val scope = cidr.text.toString().trim(); val cap = hostCap.text.toString().toIntOrNull() ?: 4096
        startCidr(scope, cap, timeoutMs)
    }

    private fun startCidr(scope: String, cap: Int, timeoutMs: Int) {
        dismissKeyboard()
        cancelActiveSearch()
        val generation = scanUiGeneration
        val port = 631
        updateRangeSearchStatus("입력한 IP 범위에서 IPP 프린터를 검색하고 있습니다. (CIDR · 631 · ${timeoutMs}ms)", StatusTone.ACTIVE)
        tcpProgressLabel.apply { text = "TCP 631 확인: 준비 중"; visibility = View.VISIBLE }
        ippProgressLabel.apply { text = "IPP 확인: 열린 포트 대기 중"; visibility = View.VISIBLE }
        discovery.scanCidr(scope, cap, port, timeoutMs, ::addPrinter,
            { phase, done, total -> main.post {
                if (generation != scanUiGeneration) return@post
                if (phase.startsWith("TCP")) {
                    tcpProgressLabel.text = "$phase: $done/$total"
                } else {
                    ippProgressLabel.text = "$phase: $done/$total · 연결 ${availablePrinterCount()}대"
                }
            } },
            { error, openCount -> main.post {
                if (generation != scanUiGeneration) return@post
                if (error == null) {
                    updateRangeSearchStatus("검색 완료: 포트 열림 ${openCount}대 · IPP 연결 ${availablePrinterCount()}대 · 연결 불가 ${unavailablePrinterCount()}대", StatusTone.SUCCESS)
                } else {
                    updateRangeSearchStatus(error, StatusTone.ERROR)
                }
                ippProgressLabel.text = if (openCount == 0) "IPP 확인: 대상 없음" else "IPP 확인 완료: $openCount/$openCount"
            } })
    }

    private fun cancelActiveSearch() {
        scanUiGeneration += 1
        mdnsFinishRunnable?.let(main::removeCallbacks)
        mdnsFinishRunnable = null
        mdnsCountdownRunnable?.let(main::removeCallbacks)
        mdnsCountdownRunnable = null
        mdnsSecondsRemaining = 0
        discovery.stopMdns()
        discovery.cancelCidrScan()
    }

    private fun stopPrinterSearch() {
        dismissKeyboard()
        cancelActiveSearch()
        updateRangeSearchStatus("네트워크 범위 검색을 중지했습니다. 이미 발견된 목록은 유지됩니다.", StatusTone.NEUTRAL)
        if (tcpProgressLabel.visibility == View.VISIBLE) tcpProgressLabel.text = "TCP 포트 확인: 중지됨"
        if (ippProgressLabel.visibility == View.VISIBLE) ippProgressLabel.text = "IPP 확인: 중지됨"
    }

    private fun clearPrinterList() {
        cancelActiveSearch()
        discovery.clearFoundPrinters()
        printers.clear()
        visiblePrinters.clear()
        selected = null
        selectedFile = null
        printerList.clearChoices()
        printerSearch.setText("")
        adapter.notifyDataSetChanged()
        printerSearchResult.text = "발견된 프린터 없음"
        selectedLabel.setTextColor(Color.rgb(52, 62, 76))
        selectedLabel.text = "프린터를 선택하세요"
        formatsLabel.text = "지원 출력 형식이 여기에 표시됩니다"
        fileLabel.text = "선택 파일 없음"
        setSubmissionMethodOptions(null)
        setColorModeOptions(null)
        setMediaOptions(null)
        setPrintScalingOptions(null)
        setExtendedPrintOptions(null)
        setContentMarginOptions(null)
        hideCidrProgress()
        updateStatus("프린터 목록이 초기화되었습니다")
    }

    private data class DirectPrinterInput(
        val host: String,
        val port: Int,
        val preferredEndpoint: PrinterEndpoint?,
    )

    private fun parseDirectPrinterInput(value: String): DirectPrinterInput {
        val input = value.trim()
        require(input.isNotBlank()) { "프린터 IP 또는 호스트명을 입력하세요" }
        if ("://" in input) {
            val endpoint = PrinterEndpoint.parse(input)
            return DirectPrinterInput(
                endpoint.host,
                endpoint.port,
                endpoint,
            )
        }
        val uri = java.net.URI("ipp://$input")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: error("올바른 프린터 IP 또는 호스트명을 입력하세요")
        val port = if (uri.port > 0) uri.port else 631
        require(port in 1..65_535) { "포트는 1~65535 범위여야 합니다" }
        return DirectPrinterInput(host, port, null)
    }

    private fun connectDirectPrinter() {
        dismissKeyboard()
        hideCidrProgress()
        val input = runCatching {
            parseDirectPrinterInput(directPrinterAddress.text.toString())
        }.getOrElse {
            updateDirectConnectionStatus(
                it.message ?: "올바른 프린터 IP를 입력하세요.",
                StatusTone.ERROR,
            )
            Toast.makeText(
                this,
                it.message ?: "올바른 프린터 IP를 입력하세요",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        updateDirectConnectionStatus(
            "${input.host}:${input.port}에서 IPP 경로·보안 방식·버전 자동 확인 중",
            StatusTone.ACTIVE,
        )
        io.execute {
            val records = runCatching {
                discovery.probeDirectPrinter(
                    input.host,
                    input.port,
                    input.preferredEndpoint,
                )
            }.getOrDefault(emptyList())
            main.post {
                if (records.isEmpty()) {
                    updateDirectConnectionStatus(
                        "직접 연결 실패: ${input.host}:${input.port}에서 표준 IPP/IPPS 경로 또는 CUPS 큐를 찾지 못했습니다",
                        StatusTone.ERROR,
                    )
                } else {
                    records.forEach(::upsertPrinter)
                    val record = records.first()
                    refreshSavedPrinterMetadata(record)
                    selectPrinter(record)
                    updateDirectConnectionStatus(
                        "직접 연결 성공: ${record.name.ifBlank { input.host }} · ${record.endpoint.uri} · IPP ${record.endpoint.ippVersion}",
                        StatusTone.SUCCESS,
                    )
                }
            }
        }
    }

    private fun lookupBgp() {
        dismissKeyboard()
        hideCidrProgress()
        updateRangeSearchStatus("인터넷 IP 대역을 조회하고 있습니다. (BGP)", StatusTone.ACTIVE)
        io.execute {
            runCatching { discovery.publicBgpScope() }
                .onSuccess { scope -> main.post {
                    val count = PrinterDiscovery.usableHostCount(scope.prefix)
                    displayBgpScope(scope)
                    updateRangeSearchStatus("BGP 범위 설정 완료: ${scope.prefix} · 대상 ${"%,d".format(count)}대", StatusTone.SUCCESS)
                } }
                .onFailure { error -> main.post { updateRangeSearchStatus("인터넷 IP 대역 조회 실패 (BGP): ${error.message}", StatusTone.ERROR) } }
        }
    }

    private fun displayBgpScope(scope: BgpScope) {
        lastBgpScope = scope
        val count = PrinterDiscovery.usableHostCount(scope.prefix)
        cidr.setText(scope.prefix)
        if (count in 1..65_536) hostCap.setText(count.toString())
        refreshNetworkInfo()
        bgpScopeLabel.apply {
            text = "확인된 BGP 대역  ${scope.prefix}\n인터넷에서 보이는 IP  ${scope.egressIp}"
            setTextColor(accentDark)
            background = roundedBackground(accentSoft, accentSoft, dp(10).toFloat())
        }
    }

    private fun refreshNetworkInfo() {
        val network = discovery.currentNetwork()
        networkInfoLabel.text = if (network == null) {
            "연결 유형 확인 불가 · 현재 IPv4 네트워크 정보를 찾지 못했습니다"
        } else {
            buildString {
                append("연결 방식  ${network.connectionType}")
                append("\n휴대폰 IP  ${network.localIp}")
                append("\n게이트웨이  ${network.gatewayIp ?: "없음"}")
                lastBgpScope?.let { append("\n인터넷에서 보이는 IP  ${it.egressIp}") }
                    ?: append("\n인터넷에서 보이는 IP  대역 조회 후 표시")
            }
        }
    }

    private fun startNetworkMonitoring() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = postNetworkRefresh()
            override fun onLost(network: Network) = postNetworkRefresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = postNetworkRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = postNetworkRefresh()
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkCallback = callback
        }
    }

    private fun postNetworkRefresh() {
        main.post {
            if (!isFinishing && ::networkInfoLabel.isInitialized) refreshNetworkInfo()
        }
    }

    private fun scanCurrentEgress24() {
        val bgp = lastBgpScope ?: run {
            Toast.makeText(this, "먼저 '인터넷 IP 대역 조회' 버튼을 눌러 인터넷에서 보이는 IP를 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        val scope = runCatching { PrinterDiscovery.containing24(bgp.egressIp) }.getOrElse {
            updateRangeSearchStatus("인터넷 IP /24 탐색 실패: 범위 계산 오류 · ${it.message}", StatusTone.ERROR)
            return
        }
        cidr.setText(scope)
        hostCap.setText("254")
        updateRangeSearchStatus("인터넷 IP ${bgp.egressIp}가 포함된 $scope 범위를 검색합니다.", StatusTone.ACTIVE)
        startCidr(scope, 254, 1_200)
    }

    private fun dismissKeyboard() {
        cidr.clearFocus()
        hostCap.clearFocus()
        if (::directPrinterAddress.isInitialized) directPrinterAddress.clearFocus()
        currentFocus?.let { view ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun rememberPrintedPrinter(printer: PrinterRecord) {
        savedPrinters.removeAll { it.endpoint.key == printer.endpoint.key }
        savedPrinters.add(0, printer.copy(source = "저장된 프린터"))
        while (savedPrinters.size > MAX_SAVED_PRINTERS) savedPrinters.removeLast()
        persistSavedPrinters()
        refreshSavedPrinterCards()
    }

    private fun printAfterSavingPrinter(request: PrintWorkRequest, requestId: Long) {
        if (!printGate.isActive(requestId)) return
        val printer = request.printer
        if (savedPrinters.any { it.endpoint.key == printer.endpoint.key }) {
            rememberPrintedPrinter(printer)
            sendOneJob(requestId)
            return
        }
        val suggestedName = suggestedPrinterName(printer)
        val input = EditText(this).apply {
            hint = "예: 우리집 프린터"
            setText(pendingPrinterAlias ?: suggestedName)
            setSingleLine()
            selectAll()
            styleInput()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "처음 출력하는 프린터입니다. 저장 목록에서 사용할 이름을 설정하세요."
                textSize = 13f
                setTextColor(muted)
                setPadding(dp(2), 0, dp(2), dp(10))
            })
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        activePrintNameInput = input
        var submitted = false
        setPrintPhase(requestId, "이름 설정 중")
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("프린터 이름 설정")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장 후 인쇄", null)
            .create()
        activePrintDialog = dialog
        dialog.setOnDismissListener {
            if (activePrintDialog === dialog) activePrintDialog = null
            activePrintNameInput = null
            if (!submitted && !recreatingUi) {
                pendingPrinterAlias = null
                finishPrintRequest(requestId)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (submitted || !printGate.isActive(requestId)) return@setOnClickListener
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "저장할 프린터 이름을 입력하세요"
                    return@setOnClickListener
                }
                savedPrinterAliases[printer.endpoint.key] = name.take(80)
                rememberPrintedPrinter(printer)
                pendingPrinterAlias = null
                submitted = true
                dialog.dismiss()
                sendOneJob(requestId)
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun suggestedPrinterName(printer: PrinterRecord): String {
        val model = printer.model.trim()
        if (model.isNotBlank()) return model
        val advertised = printer.name.trim()
        return advertised.takeIf {
            it.isNotBlank() &&
                !it.equals(printer.endpoint.host, ignoreCase = true) &&
                !it.equals("${printer.endpoint.host}:${printer.endpoint.port}", ignoreCase = true) &&
                !it.startsWith("ipp://", ignoreCase = true) &&
                !it.startsWith("ipps://", ignoreCase = true)
        }.orEmpty()
    }

    private fun connectSavedPrinter(printer: PrinterRecord) {
        directPrinterAddress.setText(printer.endpoint.uri)
        connectDirectPrinter()
    }

    private fun removeSavedPrinter(printer: PrinterRecord) {
        savedPrinters.removeAll { it.endpoint.key == printer.endpoint.key }
        savedPrinterAliases.remove(printer.endpoint.key)
        persistSavedPrinters()
        refreshSavedPrinterCards()
        Toast.makeText(this, "저장된 프린터를 삭제했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun clearAllSavedPrinters() {
        if (savedPrinters.isEmpty()) {
            Toast.makeText(this, "저장된 프린터가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        savedPrinters.clear()
        savedPrinterAliases.clear()
        persistSavedPrinters()
        refreshSavedPrinterCards()
        Toast.makeText(this, "저장된 프린터를 모두 삭제했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSavedPrinterCards() {
        if (!::savedPrintersCards.isInitialized) return
        savedPrintersCards.removeAllViews()
        if (savedPrinters.isEmpty()) {
            savedPrintersCards.addView(TextView(this).apply {
                text = "아직 저장된 프린터가 없습니다.\n출력 버튼을 누른 프린터가 여기에 저장됩니다."
                textSize = 13f
                setTextColor(muted)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBackground(Color.rgb(249, 250, 251), line, dp(10).toFloat())
            })
        } else {
            savedPrinters.forEachIndexed { index, printer ->
                savedPrintersCards.addView(
                    savedPrinterItemView(printer),
                    LinearLayout.LayoutParams(-1, -2).apply {
                        if (index > 0) topMargin = dp(8)
                    },
                )
            }
        }
        updateSavedPrintersExpander()
    }

    private fun savedPrinterItemView(printer: PrinterRecord): View {
        val isSelected = selected?.endpoint?.key == printer.endpoint.key
        val advertisedName = printer.name.trim().takeIf {
            it.isNotBlank() &&
                !it.equals(printer.endpoint.host, ignoreCase = true) &&
                !it.equals("${printer.endpoint.host}:${printer.endpoint.port}", ignoreCase = true) &&
                !it.startsWith("ipp://", ignoreCase = true) &&
                !it.startsWith("ipps://", ignoreCase = true)
        }
        val savedName = savedPrinterAliases[printer.endpoint.key]
            ?: advertisedName
            ?: printer.model.takeIf { it.isNotBlank() }
            ?: "저장된 프린터"
        val printerName = suggestedPrinterName(printer).takeIf { it.isNotBlank() }
            ?: "프린터가 장비명을 제공하지 않음"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            val cardFill = if (isSelected) accentSoft else Color.rgb(249, 250, 251)
            val cardStroke = if (isSelected) accent else line
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(36, 15, 23, 42)),
                roundedBackground(cardFill, cardStroke, dp(13).toFloat()).apply {
                    if (isSelected) setStroke(dp(2), accent)
                },
                roundedBackground(Color.WHITE, Color.WHITE, dp(13).toFloat()),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { connectSavedPrinter(printer) }
            setOnLongClickListener {
                copyPrinterDetails(printer.copy(name = savedName))
                true
            }

            val titleRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this@MainActivity).apply {
                text = savedName
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ink)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            titleRow.addView(badge("IPP ${printer.endpoint.ippVersion}", successSoft, success))
            addView(titleRow)

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(4))
            }
            actions.addView(
                badge("이름 설정", Color.rgb(245, 243, 255), Color.rgb(109, 40, 217)).apply {
                    isClickable = true
                    isFocusable = true
                    background = rippleBackground(Color.rgb(245, 243, 255), Color.rgb(221, 214, 254), 20)
                    setOnClickListener { editSavedPrinterName(printer) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) },
            )
            actions.addView(
                badge("삭제", dangerSoft, danger).apply {
                    isClickable = true
                    isFocusable = true
                    background = rippleBackground(dangerSoft, dangerSoft, 20)
                    setOnClickListener { removeSavedPrinter(printer) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) },
            )
            actions.addView(
                badge("IP 복사", Color.rgb(236, 254, 255), Color.rgb(14, 116, 144)).apply {
                    isClickable = true
                    isFocusable = true
                    background = rippleBackground(Color.rgb(236, 254, 255), Color.rgb(165, 243, 252), 20)
                    setOnClickListener { copyPrinterIp(printer) }
                },
            )
            addView(actions)

            addView(TextView(this@MainActivity).apply {
                text = "IP: ${printer.endpoint.host}"
                textSize = 12.5f
                setTextColor(accentDark)
                setPadding(0, dp(5), 0, 0)
                setTextIsSelectable(true)
            })
            addView(TextView(this@MainActivity).apply {
                text = "프린터 이름: $printerName"
                textSize = 12.5f
                setTextColor(muted)
                setPadding(0, dp(5), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextIsSelectable(true)
            })
        }
    }

    private fun refreshSavedPrinterMetadata(record: PrinterRecord) {
        val index = savedPrinters.indexOfFirst { it.endpoint.key == record.endpoint.key }
        if (index < 0) return
        savedPrinters[index] = record.copy(source = "저장된 프린터")
        persistSavedPrinters()
        refreshSavedPrinterCards()
    }

    private fun copyPrinterIp(printer: PrinterRecord) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("프린터 IP", printer.endpoint.host),
        )
        Toast.makeText(this, "프린터 IP를 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun editSavedPrinterName(printer: PrinterRecord) {
        val current = savedPrinterAliases[printer.endpoint.key] ?: printer.name.ifBlank { printer.endpoint.host }
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            selectAll()
            styleInput()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            addView(TextView(this@MainActivity).apply {
                text = "비워서 저장하면 프린터가 알려준 기본 이름으로 돌아갑니다."
                textSize = 12f
                setTextColor(muted)
                setPadding(dp(2), dp(8), dp(2), 0)
            })
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("저장된 프린터 이름 편집")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val alias = input.text.toString().trim()
                if (alias.isBlank()) savedPrinterAliases.remove(printer.endpoint.key)
                else savedPrinterAliases[printer.endpoint.key] = alias.take(80)
                persistSavedPrinters()
                refreshSavedPrinterCards()
            }
            .create()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private fun updateSavedPrintersExpander() {
        if (!::savedPrintersExpander.isInitialized) return
        val marker = if (::savedPrintersPanel.isInitialized && savedPrintersPanel.visibility == View.VISIBLE) "−" else "＋"
        savedPrintersExpander.text = "저장된 프린터 (${savedPrinters.size})  $marker"
    }

    private fun loadSavedPrinters() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val encoded = prefs.getString(PREF_SAVED_PRINTERS, null)
        if (!encoded.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(encoded)
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)
                    decodeSavedPrinter(json)?.let { printer ->
                        savedPrinters.add(printer)
                        json.optString("customName").takeIf(String::isNotBlank)?.let { alias ->
                            savedPrinterAliases[printer.endpoint.key] = alias
                        }
                    }
                }
            }
        }
        if (savedPrinters.isEmpty()) {
            val legacyUri = prefs.getString(PREF_LAST_PRINTED_PRINTER, "").orEmpty()
            runCatching { PrinterEndpoint.parse(legacyUri) }.getOrNull()?.let { endpoint ->
                savedPrinters.add(
                    PrinterRecord(
                        endpoint = endpoint,
                        name = endpoint.host,
                        model = "",
                        location = "",
                        formats = emptyList(),
                        colorModes = emptyList(),
                        defaultColorMode = null,
                        operations = emptyList(),
                        source = "저장된 프린터",
                    )
                )
                persistSavedPrinters()
            }
        }
    }

    private fun persistSavedPrinters() {
        val array = JSONArray()
        savedPrinters.forEach { printer ->
            array.put(JSONObject().apply {
                put("uri", printer.endpoint.uri)
                put("ippMajor", printer.endpoint.ippMajor)
                put("ippMinor", printer.endpoint.ippMinor)
                put("name", printer.name)
                put("model", printer.model)
                put("location", printer.location)
                put("formats", JSONArray(printer.formats))
                put("colorModes", JSONArray(printer.colorModes))
                put("defaultColorMode", printer.defaultColorMode ?: JSONObject.NULL)
                put("operations", JSONArray(printer.operations))
                savedPrinterAliases[printer.endpoint.key]?.let { put("customName", it) }
                put("mediaLeftMargins", JSONArray(printer.mediaLeftMargins))
                put("mediaRightMargins", JSONArray(printer.mediaRightMargins))
                put("mediaTopMargins", JSONArray(printer.mediaTopMargins))
                put("mediaBottomMargins", JSONArray(printer.mediaBottomMargins))
            })
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_SAVED_PRINTERS, array.toString())
            .remove(PREF_LAST_PRINTED_PRINTER)
            .apply()
    }

    private fun decodeSavedPrinter(json: JSONObject): PrinterRecord? = runCatching {
        val parsed = PrinterEndpoint.parse(json.getString("uri"))
        val endpoint = parsed.copy(
            ippMajor = json.optInt("ippMajor", parsed.ippMajor),
            ippMinor = json.optInt("ippMinor", parsed.ippMinor),
        )
        PrinterRecord(
            endpoint = endpoint,
            name = json.optString("name", endpoint.host),
            model = json.optString("model", ""),
            location = json.optString("location", ""),
            formats = json.stringList("formats"),
            colorModes = json.stringList("colorModes"),
            defaultColorMode = json.optString("defaultColorMode").takeIf { it.isNotBlank() && it != "null" },
            operations = json.intList("operations"),
            source = "저장된 프린터",
            mediaLeftMargins = json.intList("mediaLeftMargins"),
            mediaRightMargins = json.intList("mediaRightMargins"),
            mediaTopMargins = json.intList("mediaTopMargins"),
            mediaBottomMargins = json.intList("mediaBottomMargins"),
        )
    }.getOrNull()

    private fun JSONObject.stringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }

    private fun JSONObject.intList(name: String): List<Int> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.optInt(it) }
    }

    private fun showPrinterAuthDialog(
        printer: PrinterRecord,
        challengeOverride: IppAuthChallenge? = null,
        onCancelled: (() -> Unit)? = null,
        onCredentialsReady: (() -> Unit)? = null,
    ) {
        val endpoint = printer.endpoint
        val challenge = challengeOverride ?: printer.authChallenge ?: IppClient.authChallenge(endpoint)
        val unsupportedKnownScheme =
            !challenge?.schemes.isNullOrEmpty() && challenge?.supportedByApp == false
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(TextView(this).apply {
            text = buildString {
                append(endpoint.uri)
                append("\n인증 방식: ").append(challenge?.label ?: "서버 응답에서 아직 확인되지 않음")
                append("\n자격 증명은 앱이 실행되는 동안 메모리에만 보관됩니다.")
                if (!endpoint.secure) {
                    append("\n주의: IPPS가 아닌 연결에서 HTTP Basic을 사용하면 비밀번호가 암호화되지 않습니다.")
                }
                if (unsupportedKnownScheme) {
                    append("\n이 앱은 현재 HTTP Basic/Digest만 지원하므로 이 인증 방식으로는 로그인할 수 없습니다.")
                }
            }
            textSize = 12.5f
            setTextColor(if (unsupportedKnownScheme) danger else muted)
            setPadding(0, 0, 0, dp(12))
        })
        val username = EditText(this).apply {
            hint = "사용자 이름"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            styleInput()
        }
        val password = EditText(this).apply {
            hint = "비밀번호"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            styleInput()
        }
        val preemptiveBasic = CheckBox(this).apply {
            text = "인증 방식이 확인되지 않으면 HTTP Basic으로 시도"
            textSize = 12.5f
            setTextColor(ink)
            buttonTintList = ColorStateList.valueOf(accent)
            visibility = if (challenge?.schemes.isNullOrEmpty()) View.VISIBLE else View.GONE
        }
        container.addView(username, LinearLayout.LayoutParams(-1, dp(52)))
        container.addView(
            password,
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) },
        )
        container.addView(preemptiveBasic)

        var credentialsHandedOff = false
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("IPP 프린터 로그인")
            .setView(container)
            .setPositiveButton("로그인", null)
            .setNegativeButton("취소", null)
            .setNeutralButton("인증 삭제", null)
            .create()
        if (onCancelled != null) activePrintDialog = dialog
        dialog.setOnDismissListener {
            if (activePrintDialog === dialog) activePrintDialog = null
            if (!credentialsHandedOff && !recreatingUi) onCancelled?.invoke()
        }
        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = !unsupportedKnownScheme
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = IppClient.hasCredentials(endpoint)
                setOnClickListener {
                    IppClient.clearCredentials(endpoint)
                    updateStatus("${endpoint.host}의 저장된 인증 정보를 삭제했습니다", StatusTone.NEUTRAL)
                    if (printer.available) updatePrinterAuthButton(printer)
                    dialog.dismiss()
                }
            }
            positive.setOnClickListener {
                if (credentialsHandedOff || !dialog.isShowing) return@setOnClickListener
                val user = username.text.toString().trim()
                if (user.isEmpty()) {
                    username.error = "사용자 이름을 입력하세요"
                    return@setOnClickListener
                }
                IppClient.setCredentials(
                    endpoint,
                    IppCredentials(
                        username = user,
                        password = password.text.toString(),
                        preemptiveBasic = preemptiveBasic.isChecked,
                    ),
                )
                if (printer.available) {
                    updateStatus(
                        "${endpoint.host}의 인증 정보를 저장했습니다. 다음 IPP 요청에서 확인합니다.",
                        StatusTone.SUCCESS,
                    )
                    updatePrinterAuthButton(printer)
                    credentialsHandedOff = true
                    dialog.dismiss()
                    onCredentialsReady?.invoke()
                    return@setOnClickListener
                }

                positive.isEnabled = false
                username.isEnabled = false
                password.isEnabled = false
                preemptiveBasic.isEnabled = false
                updateStatus("${endpoint.host}에 로그인하는 중", StatusTone.ACTIVE)
                io.execute {
                    val authenticated = runCatching {
                        IppClient.getPrinterAttributes(endpoint, 5_000)
                    }.getOrNull()
                    main.post {
                        if (!dialog.isShowing || isFinishing || isDestroyed) return@post
                        if (authenticated != null) {
                            val updated = authenticated.copy(
                                source = "${printer.source}-authenticated",
                                authChallenge = challenge,
                            )
                            upsertPrinter(updated)
                            selectPrinter(updated)
                            updateStatus(
                                "${endpoint.host} 로그인 성공 · IPP ${updated.endpoint.ippVersion}",
                                StatusTone.SUCCESS,
                            )
                            credentialsHandedOff = true
                            dialog.dismiss()
                            onCredentialsReady?.invoke()
                        } else {
                            IppClient.clearCredentials(endpoint)
                            positive.isEnabled = true
                            username.isEnabled = true
                            password.isEnabled = true
                            preemptiveBasic.isEnabled = true
                            password.error = "로그인에 실패했습니다. 계정 또는 인증 방식을 확인하세요."
                            updateStatus("${endpoint.host} 로그인 실패", StatusTone.ERROR)
                        }
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showSelectedPrinter(printer: PrinterRecord) {
        if (!printer.available) {
            selectedLabel.setTextColor(Color.rgb(198, 40, 40))
            selectedLabel.text = if (printer.authChallenge != null) {
                "인증 필요: ${printer.endpoint.host}:${printer.endpoint.port}\n${printer.statusDetail.orEmpty()}"
            } else {
                "연결 불가: ${printer.endpoint.host}:${printer.endpoint.port}\n${printer.statusDetail.orEmpty()}"
            }
            formatsLabel.text = if (printer.authChallenge != null) {
                "로그인에 성공하면 프린터 속성과 지원 출력 형식을 다시 불러옵니다."
            } else {
                "사용 가능한 IPP 프린터 큐를 확인할 수 없어 출력할 수 없습니다"
            }
            printerAuthButton.visibility =
                if (printer.authChallenge != null) View.VISIBLE else View.GONE
            printerAuthButton.setOnClickListener { showPrinterAuthDialog(printer) }
            setSubmissionMethodOptions(null)
            setColorModeOptions(null)
            setMediaOptions(null)
            setPrintScalingOptions(null)
            setExtendedPrintOptions(null)
            setContentMarginOptions(null)
            return
        }
        selectedLabel.setTextColor(Color.rgb(52, 62, 76))
        selectedLabel.text = buildString {
            append("선택: ${printer.name.ifBlank { printer.endpoint.host }}")
            if (printer.model.isNotBlank()) append(" · ${printer.model}")
            if (printer.location.isNotBlank()) append(" · 위치 ${printer.location}")
            append("\n${printer.endpoint.uri}")
        }
        updatePrinterAuthButton(printer)
        formatsLabel.text = if (printer.formats.isEmpty()) {
            "지원 출력 형식: 프린터가 형식 정보를 제공하지 않음"
        } else {
            val conversions = PrintFormatSupport.conversionLabels(printer.formats)
            buildString {
                append("프린터 지원 형식 (${printer.formats.size}개):\n")
                append(printer.formats.joinToString(" · ") { formatLabel(it) })
                if (conversions.isNotEmpty()) append("\n앱 변환 지원: ${conversions.joinToString(" · ")}")
                append("\n출력 방식: ${printer.supportedSubmissionMethods.ifEmpty { listOf("지원 방식 미확인") }.joinToString(" · ")}")
                if (printer.supportsValidateJob) append(" · Validate-Job 사전 검사")
                if (printer.uriAuthentication.isNotEmpty()) {
                    append("\n인증 광고: ${printer.uriAuthentication.joinToString(" · ")}")
                }
                if (printer.uriSecurity.isNotEmpty()) {
                    append(" · 보안: ${printer.uriSecurity.joinToString(" · ")}")
                }
                append("\n").append(printerMarginSummary(printer))
            }
        }
        setColorModeOptions(printer)
        setMediaOptions(printer)
        setPrintScalingOptions(printer)
        setExtendedPrintOptions(printer)
        setContentMarginOptions(printer)
        setSubmissionMethodOptions(printer)
    }

    private fun updatePrinterAuthButton(printer: PrinterRecord) {
        printerAuthButton.visibility = View.VISIBLE
        printerAuthButton.text = if (IppClient.hasCredentials(printer.endpoint)) {
            "프린터 로그인 변경 / 삭제"
        } else {
            "프린터 로그인 / 자격 증명"
        }
        printerAuthButton.setOnClickListener { showPrinterAuthDialog(printer) }
    }

    private fun setContentMarginOptions(printer: PrinterRecord?, preserve: Boolean = false) {
        if (!::contentMarginSpinner.isInitialized) return
        val previous = if (preserve) contentMarginValues.getOrNull(contentMarginSpinner.selectedItemPosition) else null
        val advertised = advertisedPrinterMargins(printer)
        contentMarginValues = listOf(-1, 0, 20, 43, 77)
        contentMarginDefaultIndex = if (advertised == null) 1 else 0
        contentMarginSpinner.adapter = scrollableSpinnerAdapter(listOf(
            if (advertised == null) "프린터 광고 여백 사용 (정보 없음)" else "프린터 광고 여백 사용",
            "없음 (0mm)", "좁게 (약 7mm)", "보통 (약 15mm)", "넓게 (약 27mm)",
        ))
        contentMarginSpinner.setSelection(previous?.let { contentMarginValues.indexOf(it).takeIf { index -> index >= 0 } } ?: contentMarginDefaultIndex)
        contentMarginDetailLabel.text = if (advertised == null) "선택한 용지의 출력 가능 영역을 확인할 수 없습니다." else
            "선택 용지 광고값: 왼쪽 ${"%.1f".format(advertised[0])}mm · 위 ${"%.1f".format(advertised[1])}mm\n" +
                "오른쪽 ${"%.1f".format(advertised[2])}mm · 아래 ${"%.1f".format(advertised[3])}mm"
        contentMarginDetailLabel.visibility = View.VISIBLE
    }

    private fun setSubmissionMethodOptions(printer: PrinterRecord?) {
        submissionMethodValues = buildList {
            add(JobSubmissionMethod.AUTO)
            if (printer?.supportsCreateSendDocument == true) add(JobSubmissionMethod.CREATE_JOB_SEND_DOCUMENT)
            if (printer?.supportsPrintJob == true) add(JobSubmissionMethod.PRINT_JOB)
        }
        val labels = submissionMethodValues.map { method ->
            when (method) {
                JobSubmissionMethod.AUTO -> "자동 (권장)"
                JobSubmissionMethod.CREATE_JOB_SEND_DOCUMENT -> "Create-Job + Send-Document"
                JobSubmissionMethod.PRINT_JOB -> "Print-Job"
            }
        }
        submissionMethodSpinner.adapter = scrollableSpinnerAdapter(labels)
        submissionMethodSpinner.isEnabled = submissionMethodValues.size > 1
    }

    private fun setColorModeOptions(printer: PrinterRecord?) {
        val modes = printer?.colorModes.orEmpty().map { it.lowercase() }.distinct()
        colorModeValues = listOf<String?>(null) + modes
        val defaultLabel = printer?.defaultColorMode?.let { "프린터 기본값 (${colorModeLabel(it)})" } ?: "프린터 기본값"
        val labels = listOf(defaultLabel) + modes.map(::colorModeLabel)
        colorModeSpinner.adapter = scrollableSpinnerAdapter(labels)
        colorModeSpinner.isEnabled = modes.isNotEmpty()
        val monochrome = listOf("monochrome", "process-monochrome", "bi-level")
            .firstNotNullOfOrNull { preferred -> modes.indexOf(preferred).takeIf { it >= 0 } }
        colorModeSpinner.setSelection(if (monochrome == null) 0 else monochrome + 1)
    }

    private fun colorModeLabel(mode: String): String = when (mode.lowercase()) {
        "color" -> "컬러"
        "monochrome", "bi-level", "process-monochrome" -> "흑백"
        "auto" -> "자동"
        else -> mode
    }

    private fun setMediaOptions(printer: PrinterRecord?) {
        val values = printer?.media.orEmpty().map { it.lowercase() }.distinct()
        mediaValues = listOf<String?>(null) + values
        val defaultLabel = printer?.defaultMedia?.takeIf { it.isNotBlank() }
            ?.let { "프린터 기본 용지 (${mediaLabel(it)})" }
            ?: "프린터에 맡김 (기본 용지 정보 없음)"
        mediaSpinner.adapter = scrollableSpinnerAdapter(listOf(defaultLabel) + values.map(::mediaLabel))
        mediaSpinner.isEnabled = values.isNotEmpty()
    }

    private fun selectedMedia(printer: PrinterRecord?): String? =
        mediaValues.getOrNull(mediaSpinner.selectedItemPosition)
            ?: printer?.defaultMedia?.takeIf { it.isNotBlank() }

    private fun mediaLabel(media: String): String = when (media.lowercase()) {
        "iso_a4_210x297mm" -> "A4 (210×297mm)"
        "iso_a3_297x420mm" -> "A3 (297×420mm)"
        "iso_a5_148x210mm" -> "A5 (148×210mm)"
        "na_letter_8.5x11in" -> "Letter (8.5×11in)"
        "na_legal_8.5x14in" -> "Legal (8.5×14in)"
        else -> media
    }

    private fun setPrintScalingOptions(printer: PrinterRecord?) {
        val values = printer?.printScalings.orEmpty().map { it.lowercase() }.distinct()
        printScalingValues = listOf<String?>(null) + values
        val defaultLabel = printer?.defaultPrintScaling?.let { "프린터 기본값 (${printScalingLabel(it)})" } ?: "프린터 기본값"
        printScalingSpinner.adapter = scrollableSpinnerAdapter(listOf(defaultLabel) + values.map(::printScalingLabel))
        printScalingSpinner.isEnabled = values.isNotEmpty()
    }

    private fun printScalingLabel(value: String): String = when (value.lowercase()) {
        "auto" -> "자동"
        "auto-fit" -> "필요할 때만 용지에 맞춤"
        "fit" -> "용지 안에 맞춤"
        "fill" -> "용지를 채움 (일부 잘릴 수 있음)"
        "none" -> "원본 크기"
        else -> value
    }

    private fun setExtendedPrintOptions(printer: PrinterRecord?) {
        sidesValues = configureOptions(sidesSpinner, printer?.sides.orEmpty(), printer?.defaultSides, ::sidesLabel)
        orientationValues = configureOptions(orientationSpinner, printer?.orientations.orEmpty(), printer?.defaultOrientation, ::orientationLabel)
        printQualityValues = configureOptions(printQualitySpinner, printer?.printQualities.orEmpty(), printer?.defaultPrintQuality, ::qualityLabel)
        resolutionValues = configureOptions(resolutionSpinner, printer?.resolutions.orEmpty(), printer?.defaultResolution) { it.label }
        configurePageControls(printer)
        xPositionValues = configureOptions(xPositionSpinner, printer?.xImagePositions.orEmpty(), printer?.defaultXImagePosition, ::xPositionLabel)
        yPositionValues = configureOptions(yPositionSpinner, printer?.yImagePositions.orEmpty(), printer?.defaultYImagePosition, ::yPositionLabel)
        val copies = printer?.copiesSupported
        copiesInput.setText("1")
        copiesInput.hint = copies?.let { "${it.lower}~${it.upper}부" } ?: "1"
        copiesInput.isEnabled = copies != null && copies.upper > 1
        pageRangesInput.setText("")
    }

    private fun <T> configureOptions(
        spinner: Spinner,
        supported: List<T>,
        defaultValue: T?,
        label: (T) -> String,
    ): List<T?> {
        val values = supported.distinct()
        val defaultLabel = defaultValue?.let { "프린터 기본값 (${label(it)})" } ?: "프린터 기본값"
        spinner.adapter = scrollableSpinnerAdapter(listOf(defaultLabel) + values.map(label))
        spinner.isEnabled = values.isNotEmpty()
        return listOf<T?>(null) + values
    }

    private fun sidesLabel(value: String): String = when (value.lowercase()) {
        "one-sided" -> "단면"
        "two-sided-long-edge" -> "양면 · 긴쪽 넘김"
        "two-sided-short-edge" -> "양면 · 짧은쪽 넘김"
        else -> value
    }

    private fun orientationLabel(value: Int): String = when (value) {
        3 -> "세로"
        4 -> "가로"
        5 -> "역방향 가로"
        6 -> "역방향 세로"
        else -> "방향 코드 $value"
    }

    private fun qualityLabel(value: Int): String = when (value) {
        3 -> "초안"
        4 -> "보통"
        5 -> "고품질"
        else -> "품질 코드 $value"
    }

    private fun xPositionLabel(value: String): String = when (value.lowercase()) {
        "left" -> "왼쪽"
        "center" -> "가운데"
        "right" -> "오른쪽"
        "none" -> "프린터 배치"
        else -> value
    }

    private fun yPositionLabel(value: String): String = when (value.lowercase()) {
        "top" -> "위쪽"
        "center" -> "가운데"
        "bottom" -> "아래쪽"
        "none" -> "프린터 배치"
        else -> value
    }

    private fun formatLabel(mime: String): String = when (PrintFormatSupport.normalize(mime)) {
        "application/pdf" -> "PDF"
        "text/plain" -> "일반 텍스트"
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "image/tiff" -> "TIFF"
        "image/webp" -> "WebP"
        "image/bmp" -> "BMP"
        "image/heic", "image/heif" -> "HEIF"
        "application/postscript" -> "PostScript"
        "application/vnd.hp-pcl" -> "HP PCL"
        "application/vnd.hp-pclxl" -> "HP PCL XL"
        "application/x-color-gdi" -> "Samsung COLOR-GDI"
        "image/pwg-raster" -> "PWG Raster"
        "image/urf" -> "Apple Raster(URF)"
        "application/pclm" -> "PCLm"
        "application/octet-stream" -> "자동 형식 감지"
        else -> mime
    }

    private fun chooseFile() {
        selected ?: run { Toast.makeText(this, "먼저 프린터를 선택하세요", Toast.LENGTH_SHORT).show(); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            type = "*/*"
        }, 41)
    }

    @Deprecated("Legacy callback retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            cancelPendingPreview()
            selectedFile = data?.data
            conversionTargetMimes = listOf(null)
            lastConversionMime = null
            selectedFile?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                fileLabel.text = fileDisplayName(uri)
            }
            selectedFileMime = null
            inspectSelectedDocument()
            updateDocumentInputAvailability()
        }
    }

    private enum class DocumentInputSource { NONE, FILE, URL }

    private fun activeDocumentInputSource(): DocumentInputSource = when {
        selectedFile != null -> DocumentInputSource.FILE
        remoteDocumentUri.text.isNotBlank() -> DocumentInputSource.URL
        else -> DocumentInputSource.NONE
    }

    private fun setDocumentInputEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.45f
    }

    private fun setSpinnerRowVisible(spinner: Spinner, visible: Boolean) {
        (spinner.parent as? View)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun selectedConversionMime(): String? =
        conversionTargetMimes.getOrNull(conversionFormatSpinner.selectedItemPosition)

    private fun conversionFormatLabel(mime: String): String = when (PrintFormatSupport.normalize(mime)) {
        PrintFormatSupport.PDF -> "PDF"
        PrintFormatSupport.POSTSCRIPT -> "PostScript"
        PrintFormatSupport.PWG_RASTER -> "PWG Raster"
        PrintFormatSupport.URF -> "Apple Raster (URF)"
        PrintFormatSupport.PCL -> "PCL (흑백)"
        "image/png" -> "PNG"
        "image/jpeg" -> "JPEG"
        else -> mime
    }

    private fun conversionFormatNote(mime: String, source: String?): String = when (PrintFormatSupport.normalize(mime)) {
        PrintFormatSupport.PDF -> when {
            source == PrintFormatSupport.PDF -> "PDF의 글자와 벡터를 유지합니다. 페이지 크기가 선택 용지와 다르면 용지에 맞춰 배치합니다."
            PrintCapabilityPolicy.isPhoto(source) -> "사진 원본 픽셀을 유지해 선택한 용지 크기의 PDF 페이지에 배치합니다."
            source == "text/plain" -> "TXT를 PDF 페이지로 만듭니다. TXT에는 글자 크기·줄바꿈 서식이 없어 기본 글꼴 15pt로 줄을 나누고 페이지를 이어 만듭니다."
            else -> "PDF 페이지를 만들고 프린터가 받는 형식으로 전송합니다."
        }
        PrintFormatSupport.POSTSCRIPT -> "페이지를 선택한 dpi의 픽셀로 바꿉니다. 글자와 벡터도 픽셀이 됩니다."
        PrintFormatSupport.PWG_RASTER -> "페이지가 픽셀 이미지로 바뀝니다. 글자와 벡터는 픽셀이 되며 이미지 데이터는 무손실입니다."
        PrintFormatSupport.URF -> "페이지가 픽셀 이미지로 바뀝니다. 글자와 벡터는 픽셀이 되며 이미지 데이터는 무손실입니다."
        PrintFormatSupport.PCL -> "1비트 흑백으로 변환합니다. 밝은 회색과 옅은 선은 사라질 수 있습니다. 미리보기에서 확인하세요."
        "image/png" -> "PNG는 무손실이지만 한 페이지 문서만 보낼 수 있습니다. 글자와 벡터는 픽셀이 됩니다."
        "image/jpeg" -> "한 페이지 문서만 보낼 수 있습니다."
        else -> "선택한 형식으로 새 인쇄 파일을 만듭니다."
    }

    private fun outputNeedsDpi(mime: String): Boolean =
        PrintFormatSupport.normalize(mime) != PrintFormatSupport.PDF

    private fun hasContentLayoutRequest(): Boolean {
        if (!::contentHorizontalSpinner.isInitialized || !::contentVerticalSpinner.isInitialized) return false
        return contentHorizontalSpinner.selectedItemPosition != 1 ||
            contentVerticalSpinner.selectedItemPosition != 1 ||
            contentScaleSpinner.selectedItemPosition != 0 ||
            contentMarginSpinner.selectedItemPosition != contentMarginDefaultIndex ||
            contentScalePercentInput.text.toString().trim().toFloatOrNull()?.let { it != 100f } == true ||
            contentOffsetXInput.text.toString().trim().toFloatOrNull()?.let { it != 0f } == true ||
            contentOffsetYInput.text.toString().trim().toFloatOrNull()?.let { it != 0f } == true
    }

    private fun hasExplicitLayoutRequest(): Boolean = hasContentLayoutRequest() ||
        pageRangesInput.text.isNotBlank() || (numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition) ?: selected?.defaultNumberUp ?: 1) > 1

    private fun updateConversionUi(printer: PrinterRecord?) {
        if (!::conversionPanel.isInitialized || !::conversionFormatSpinner.isInitialized) return
        val source = selectedFileMime?.let(PrintFormatSupport::normalize)
        if (selectedFile == null || source == null || printer == null) {
            conversionPanel.visibility = View.GONE
            conversionTargetMimes = listOf(null)
            lastConversionMime = null
            return
        }

        if (!PrintCapabilityPolicy.appCanLayout(source)) {
            conversionTargetMimes = listOf(null)
            conversionPanel.visibility = View.VISIBLE
            conversionReasonLabel.text = "앱에서 미리보기와 페이지 배치를 처리할 수 없는 파일 형식입니다."
            setSpinnerRowVisible(conversionFormatSpinner, false)
            conversionFormatSpinner.isEnabled = false
            conversionDescriptionLabel.text = PrintFormatSupport.unsupportedFileMessage(
                fileLabel.text.toString(), source, printer.formats,
            ).removePrefix("전송 실패: ")
            conversionDescriptionLabel.visibility = View.VISIBLE
            conversionOptionsPanel.visibility = View.GONE
            lastConversionMime = null
            return
        }

        val directlySupported = PrintFormatSupport.supports(printer.formats, source)
        val conversionNeeded = source != PrintFormatSupport.PDF || !directlySupported
        if (!conversionNeeded) {
            conversionPanel.visibility = View.GONE
            conversionTargetMimes = listOf(null)
            lastConversionMime = null
            return
        }

        val selectedColorMode = colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition)
            ?: printer.defaultColorMode
        val selectedSides = sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: printer.defaultSides
        val targets = DocumentOutputPlanner.conversionTargets(printer, selectedColorMode, selectedSides)
        val automaticMime = DocumentOutputPlanner.automaticOutputMime(printer, selectedColorMode, selectedSides)
        if (automaticMime == null) {
            conversionTargetMimes = listOf(null)
            conversionPanel.visibility = View.VISIBLE
            val outputProblem = DocumentOutputPlanner.automaticOutputProblem(printer, selectedColorMode, selectedSides)
            conversionReasonLabel.text = if (outputProblem != null) {
                "현재 인쇄 설정과 함께 쓸 수 있는 변환 형식이 없습니다."
            } else "프린터가 앱에서 생성할 수 있는 출력 형식을 광고하지 않습니다."
            setSpinnerRowVisible(conversionFormatSpinner, false)
            conversionFormatSpinner.isEnabled = false
            conversionDescriptionLabel.text = outputProblem ?: "프린터가 앱에서 생성할 수 있는 PDF, PostScript, PWG Raster, URF, PCL, JPEG, PNG 형식을 광고하지 않습니다. PDF로 바꾸더라도 이 프린터로는 전송할 수 없습니다."
            conversionDescriptionLabel.visibility = View.VISIBLE
            conversionOptionsPanel.visibility = View.GONE
            lastConversionMime = null
            return
        }

        val alternatives = targets.filterNot { PrintFormatSupport.normalize(it) == PrintFormatSupport.normalize(automaticMime) }
        val previous = selectedConversionMime()
        val nextValues = listOf<String?>(null) + alternatives
        if (nextValues != conversionTargetMimes) {
            conversionTargetMimes = nextValues
            updatingConversionTargets = true
            conversionFormatSpinner.adapter = scrollableSpinnerAdapter(
                listOf("자동 (${conversionFormatLabel(automaticMime)})") + alternatives.map(::conversionFormatLabel),
            )
            conversionFormatSpinner.setSelection(nextValues.indexOf(previous).takeIf { it >= 0 } ?: 0)
            updatingConversionTargets = false
        }
        conversionPanel.visibility = View.VISIBLE
        conversionReasonLabel.text = when {
            source == PrintFormatSupport.PDF -> "프린터가 PDF를 받지 않아 선택한 용지 크기에 맞춘 출력 형식으로 자동 변환합니다."
            !directlySupported -> "프린터가 ${formatLabel(source)}를 받지 않아 앱이 만들 수 있는 형식으로 자동 변환합니다."
            else -> "미리보기와 같은 페이지 배치를 위해 문서를 앱에서 준비한 뒤 전송합니다."
        }
        setSpinnerRowVisible(conversionFormatSpinner, alternatives.isNotEmpty())
        conversionFormatSpinner.isEnabled = alternatives.isNotEmpty()
        val target = selectedConversionMime()?.let(PrintFormatSupport::normalize)
            ?: PrintFormatSupport.normalize(automaticMime)

        if (lastConversionMime != target) {
            if (target == PrintFormatSupport.POSTSCRIPT) {
                psEncodingSpinner.adapter = scrollableSpinnerAdapter(
                    listOf("무손실 RGB", "JPEG 품질 100 (손실)", "JPEG 품질 90 (손실)"),
                )
                // At page-sized automatic resolution, maximum-quality DCT avoids
                // sending multi-megabyte uncompressed RGB bands to the printer.
                psEncodingSpinner.setSelection(1)
            } else if (target == "image/jpeg") {
                psEncodingSpinner.adapter = scrollableSpinnerAdapter(
                    listOf("품질 100 (손실, 최고 품질)", "품질 90 (손실)"),
                )
                psEncodingSpinner.setSelection(0)
            }
            lastConversionMime = target
        }
        val needsDpi = outputNeedsDpi(target)
        val automaticDpi = AutomaticRasterPolicy.outputDpi(printer, target)
        val selectedDpi = rasterDpiValues.getOrNull(rasterDpiSpinner.selectedItemPosition)
        val effectiveDpi = if (needsDpi) selectedDpi ?: automaticDpi else null
        val encodingNote = when (PrintFormatSupport.normalize(target)) {
            PrintFormatSupport.POSTSCRIPT -> if (psEncodingSpinner.selectedItemPosition == 0) {
                "현재 RGB 데이터는 무손실입니다."
            } else {
                "현재 PostScript 내부에 JPEG 품질 ${if (psEncodingSpinner.selectedItemPosition == 2) 90 else 100} 압축을 사용합니다. 손실 압축입니다."
            }
            "image/jpeg" -> "현재 JPEG 품질 ${if (psEncodingSpinner.selectedItemPosition == 1) 90 else 100}을 사용합니다. 손실 압축이며 100도 무손실은 아닙니다."
            else -> ""
        }
        val rasterProblem = printer?.let { PrinterOutputPolicy.rasterProblem(it, target,
            effectiveDpi, sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: it.defaultSides) }
        val pixelNote = if (target == PrintFormatSupport.PDF && source == PrintFormatSupport.PDF) "" else
            "\n사진·래스터 변환은 8비트 RGB를 사용하며 투명 부분은 흰색이 됩니다. 원본 색상 프로파일·HDR은 유지되지 않습니다. 다중 프레임 이미지는 변환하지 않습니다."
        val pagePixels = if (target != PrintFormatSupport.PDF) runCatching {
            val dpi = effectiveDpi ?: return@runCatching ""
            val size = PrinterOutputPolicy.pageSize(printer, selectedMedia(printer)) ?: return@runCatching ""
            val (width, height) = RasterBandPolicy.dimensions(size.first, size.second, dpi)
            "\n선택 용지 기준 ${width} × ${height}px (${dpi}dpi)"
        }.getOrDefault("") else ""
        val automaticNote = if (selectedConversionMime() == null) "자동으로 ${conversionFormatLabel(target)} 형식에 맞춰 준비합니다. "
            else "선택한 ${conversionFormatLabel(target)} 형식으로 준비합니다. "
        val dpiNote = when {
            !needsDpi -> ""
            selectedDpi == null -> "\n자동 해상도: ${effectiveDpi ?: AutomaticRasterPolicy.STANDARD_DPI}dpi. 필요하면 아래에서 직접 바꿀 수 있습니다."
            else -> "\n선택한 변환 해상도: ${selectedDpi}dpi. 앱이 자동으로 낮추지 않습니다."
        }
        conversionDescriptionLabel.text = automaticNote +
            listOf(conversionFormatNote(target, source), encodingNote, rasterProblem.orEmpty()).filter { it.isNotBlank() }.joinToString(" ") + pagePixels + pixelNote +
            dpiNote
        conversionDescriptionLabel.visibility = View.VISIBLE
        val showEncoding = target == PrintFormatSupport.POSTSCRIPT || target == "image/jpeg"
        conversionOptionsPanel.visibility = if (needsDpi || showEncoding) View.VISIBLE else View.GONE
        setSpinnerRowVisible(rasterDpiSpinner, needsDpi)
        setSpinnerRowVisible(psEncodingSpinner, showEncoding)
        conversionOptionsHelp.text = "자동은 보통 300dpi를 사용하고, PWG Raster·URF는 프린터 광고 해상도에 맞춥니다. 직접 고른 값은 자동으로 낮추지 않습니다."
        conversionOptionsHelp.visibility = if (needsDpi) View.VISIBLE else View.GONE
    }

    private fun updateDocumentInputAvailability() {
        if (!::chooseFileButton.isInitialized || !::remoteDocumentUri.isInitialized) return
        when (activeDocumentInputSource()) {
            DocumentInputSource.NONE -> {
                setDocumentInputEnabled(chooseFileButton, true)
                setDocumentInputEnabled(remoteDocumentUri, true)
            }
            DocumentInputSource.FILE -> {
                setDocumentInputEnabled(chooseFileButton, true)
                setDocumentInputEnabled(remoteDocumentUri, false)
            }
            DocumentInputSource.URL -> {
                setDocumentInputEnabled(chooseFileButton, false)
                setDocumentInputEnabled(remoteDocumentUri, true)
            }
        }
        setDocumentInputEnabled(clearFileButton, selectedFile != null)
        if (!restoringEditor) configurePageControls(selected)
    }

    private fun inspectSelectedDocument() {
        val uri = selectedFile ?: return
        val generation = ++documentInspectionGeneration
        io.execute {
            val mime = runCatching { filePreparer.inspectMime(uri) }
            main.post {
                if (generation != documentInspectionGeneration || uri != selectedFile || isFinishing || isDestroyed) return@post
                selectedFileMime = mime.getOrNull()
                configurePageControls(selected)
                mime.exceptionOrNull()?.let {
                    updateStatus("문서를 확인할 수 없습니다: ${it.message}", StatusTone.ERROR)
                }
            }
        }
    }

    private fun configurePageControls(printer: PrinterRecord?) {
        if (!::numberUpSpinner.isInitialized || !::pageRangesInput.isInitialized) return
        val remote = ::remoteDocumentUri.isInitialized && remoteDocumentUri.text.isNotBlank() && selectedFile == null
        val controls = PrintCapabilityPolicy.layoutControls(printer, selectedFileMime, remote)
        val previous = numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition)
        numberUpValues = listOf<Int?>(null) + controls.numberUpChoices
        val defaultLabel = printer?.defaultNumberUp?.let { "프린터 기본값 (${it}페이지)" } ?: "프린터 기본값"
        numberUpSpinner.adapter = scrollableSpinnerAdapter(listOf(defaultLabel) + controls.numberUpChoices.map { "${it}페이지" })
        numberUpSpinner.isEnabled = controls.numberUpChoices.isNotEmpty()
        numberUpSpinner.setSelection(numberUpValues.indexOf(previous).coerceAtLeast(0))
        submissionMethodSpinner.isEnabled = !remote && submissionMethodValues.size > 1
        pageRangesInput.isEnabled = controls.pageRangesAllowed
        pageRangesInput.hint = controls.pageRangesHint
        if (::pageHandlingLabel.isInitialized) pageHandlingLabel.text = when {
            controls.appCanLayout -> "페이지 범위와 모아찍기는 앱에서 문서에 적용합니다."
            remote -> "문서 URL은 프린터가 직접 처리합니다. 프린터가 지원하는 옵션만 선택할 수 있습니다."
            selectedFile != null -> "이 형식은 앱에서 페이지를 재배치하지 않습니다. 지원되는 옵션은 프린터에서 처리합니다."
            else -> "PDF·사진·텍스트를 선택하면 앱의 페이지 선택과 모아찍기를 사용할 수 있습니다."
        }
        if (::contentHorizontalSpinner.isInitialized) {
            listOf<View>(contentHorizontalSpinner, contentVerticalSpinner, contentScaleSpinner,
                contentMarginSpinner, contentScalePercentInput, contentOffsetXInput, contentOffsetYInput,
                psEncodingSpinner).forEach {
                setDocumentInputEnabled(it, controls.appCanLayout)
            }
        }
        if (!restoringEditor) {
            updatingLayoutControls = true
            try {
                if (controls.appCanLayout) {
                    // Carry explicit native choices into the single visible layout editor.
                    val nativeX = xPositionValues.getOrNull(xPositionSpinner.selectedItemPosition)
                    val nativeY = yPositionValues.getOrNull(yPositionSpinner.selectedItemPosition)
                    val nativeScale = printScalingValues.getOrNull(printScalingSpinner.selectedItemPosition)
                    if (nativeX != null || nativeY != null || nativeScale != null) {
                        when (nativeX) { "left" -> 0; "center" -> 1; "right" -> 2; else -> null }?.let(contentHorizontalSpinner::setSelection)
                        when (nativeY) { "top" -> 0; "center" -> 1; "bottom" -> 2; else -> null }?.let(contentVerticalSpinner::setSelection)
                        contentScaleSpinner.setSelection(when (nativeScale) {
                            "fill" -> 2; "none" -> 5; "auto-fit", "shrink-to-fit" -> 6
                            "fit", "auto" -> 1
                            else -> contentScaleSpinner.selectedItemPosition.takeIf { it > 0 } ?: 1
                        })
                        Toast.makeText(this, "선택한 맞춤·위치를 페이지 배치로 옮겼습니다", Toast.LENGTH_SHORT).show()
                    }
                }
                listOf(printScalingSpinner, xPositionSpinner, yPositionSpinner).forEach {
                    setSpinnerRowVisible(it, !controls.appCanLayout)
                    if (controls.appCanLayout) it.setSelection(0)
                }
            } finally { updatingLayoutControls = false }
        }
        if (!restoringEditor) updateConversionUi(printer)
    }

    private fun installConversionOptionListeners() {
        val refresh = { if (!restoringEditor && !updatingLayoutControls) updateConversionUi(selected) }
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
        }
        listOf(
            contentHorizontalSpinner, contentVerticalSpinner, contentScaleSpinner, contentMarginSpinner,
            mediaSpinner, orientationSpinner, printScalingSpinner, xPositionSpinner, yPositionSpinner,
            numberUpSpinner, colorModeSpinner, sidesSpinner,
        ).forEach { it.onItemSelectedListener = spinnerListener }
        conversionFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingConversionTargets && !restoringEditor) updateConversionUi(selected)
            }
        }
        mediaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!restoringEditor && !updatingLayoutControls) { setContentMarginOptions(selected, preserve = true); refresh() }
            }
        }
        rasterDpiSpinner.onItemSelectedListener = spinnerListener
        psEncodingSpinner.onItemSelectedListener = spinnerListener
        listOf(contentScalePercentInput, contentOffsetXInput, contentOffsetYInput, pageRangesInput).forEach { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(value: Editable?) = Unit
            })
        }
    }

    private fun validateAndPrint() {
        if (printGate.isBusy()) {
            Toast.makeText(this, "인쇄 요청을 처리 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        val printer = selected ?: run { Toast.makeText(this, "프린터를 선택하세요", Toast.LENGTH_SHORT).show(); return }
        val isRemote = selectedFile == null && remoteDocumentUri.text.isNotBlank()
        if (!PrintCapabilityPolicy.supportsSubmission(printer, isRemote)) {
            Toast.makeText(this, if (isRemote) "이 프린터는 문서 URL 인쇄(Print-URI 또는 Send-URI)를 지원하지 않습니다" else "이 프린터는 파일 인쇄(Print-Job 또는 Send-Document)를 지원하지 않습니다", Toast.LENGTH_LONG).show()
            return
        }
        if (selectedFile == null && remoteDocumentUri.text.isBlank()) {
            Toast.makeText(this, "파일을 선택하거나 문서 URL을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val documentInputCount = listOf(
            selectedFile != null,
            remoteDocumentUri.text.isNotBlank(),
        ).count { it }
        if (documentInputCount > 1) {
            Toast.makeText(this, "파일과 문서 URL 중 하나만 사용할 수 있습니다", Toast.LENGTH_LONG).show()
            return
        }
        if (selectedFile == null && remoteDocumentUri.text.isNotBlank()) {
            val remote = runCatching { java.net.URI(remoteDocumentUri.text.toString().trim()) }.getOrNull()
            if (remote?.scheme?.lowercase() !in setOf("http", "https") || remote?.host.isNullOrBlank()) {
                Toast.makeText(this, "문서 URL은 올바른 HTTP 또는 HTTPS 주소여야 합니다", Toast.LENGTH_LONG).show()
                return
            }
        }
        val request = runCatching { capturePrintRequest(printer) }.getOrElse {
            Toast.makeText(this, it.message ?: "출력 옵션을 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        cancelPendingPreview()
        runCatching { PrintWorkManager.reserve(request) }.getOrElse {
            updateStatus(it.message ?: "인쇄 작업을 시작할 수 없습니다", StatusTone.ERROR); return
        } ?: return
        printButton.isEnabled = false
        printButton.text = "인쇄 준비 중"
    }

    private fun capturePrintRequest(printer: PrinterRecord): PrintWorkRequest {
        require(selectedFile == null || selectedFileMime != null) { "파일 형식을 확인 중입니다. 확인이 끝난 뒤 인쇄하세요." }
        return PrintWorkRequest(
        printer = printer,
        file = selectedFile,
        remoteUri = remoteDocumentUri.text.toString().trim().takeIf { selectedFile == null && it.isNotBlank() },
        settings = if (selectedFile == null && remoteDocumentUri.text.isNotBlank()) {
            FilePreparationSettings(ContentLayoutOptions(), false, emptyList(), 1)
        } else filePreparationSettings(),
        options = IppJobOptions(
            copies = selectedCopies(printer),
            pageRanges = selectedPageRanges(),
            colorMode = colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition),
            media = selectedMedia(printer),
            printScaling = printScalingValues.getOrNull(printScalingSpinner.selectedItemPosition),
            sides = sidesValues.getOrNull(sidesSpinner.selectedItemPosition),
            orientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition),
            printQuality = printQualityValues.getOrNull(printQualitySpinner.selectedItemPosition),
            resolution = resolutionValues.getOrNull(resolutionSpinner.selectedItemPosition),
            numberUp = numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition),
            xImagePosition = xPositionValues.getOrNull(xPositionSpinner.selectedItemPosition),
            yImagePosition = yPositionValues.getOrNull(yPositionSpinner.selectedItemPosition),
        ),
        submissionMethod = submissionMethodValues.getOrElse(submissionMethodSpinner.selectedItemPosition) { JobSubmissionMethod.AUTO },
    )

    }

    private fun setPrintPhase(requestId: Long, label: String) {
        if (!printGate.isActive(requestId) || isFinishing || isDestroyed) return
        printButton.isEnabled = false
        printButton.text = label
        printButton.alpha = 0.65f
    }

    private fun finishPrintRequest(requestId: Long) {
        PrintWorkManager.cancel(requestId)
    }

    private fun filePreparationSettings(forPreview: Boolean = false): FilePreparationSettings {
        val source = selectedFileMime?.let(PrintFormatSupport::normalize)
        val canLayout = PrintCapabilityPolicy.appCanLayout(source)
        val layoutRequested = PrintCapabilityPolicy.usesAppLayout(source, hasExplicitLayoutRequest())
        require(selectedFile == null || source != null) { "파일 형식을 확인 중입니다. 잠시 후 다시 눌러 주세요." }
        val printer = selected ?: error("프린터를 선택하세요")
        if (source != null && !canLayout) {
            error(PrintFormatSupport.unsupportedFileMessage(fileLabel.text.toString(), source, printer.formats))
        }
        val directlySupportedPdf = source == PrintFormatSupport.PDF && PrintFormatSupport.supports(printer.formats, source)
        val requestedOutput = if (source != PrintFormatSupport.PDF || !directlySupportedPdf) selectedConversionMime() else null
        val outputPlan = source?.let {
            DocumentOutputPlanner.plan(
                printer.formats, it, layoutRequested, false,
                colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition) ?: printer.defaultColorMode,
                requestedOutput,
                printer = printer,
                sidesMode = sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: printer.defaultSides,
            )
        }
        val changed = canLayout && hasContentLayoutRequest()
        val numberUp = numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition) ?: selected?.defaultNumberUp ?: 1
        val printerPageSizeKnown = source == PrintFormatSupport.PDF &&
            PrinterOutputPolicy.pageSize(printer, selectedMedia(printer)) != null
        val needsPaper = canLayout && (changed || numberUp > 1 || source != PrintFormatSupport.PDF ||
            outputPlan?.mime != PrintFormatSupport.PDF || printerPageSizeKnown)
        val layout = selectedContentLayout(needsPaper)
        if (!forPreview && outputPlan != null) {
            PrinterOutputPolicy.rasterProblem(printer, outputPlan.mime,
                layout.rasterDpi.takeIf { it > 0 } ?: AutomaticRasterPolicy.outputDpi(printer, outputPlan.mime),
                sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: printer.defaultSides)?.let { error(it) }
        }
        return FilePreparationSettings(
            layout, changed, selectedPageRanges(),
            numberUp,
            colorMode = colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition),
            requestedOutputMime = requestedOutput,
            printQuality = printQualityValues.getOrNull(printQualitySpinner.selectedItemPosition),
            expectedSourceMime = source,
            sidesMode = sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: selected?.defaultSides,
        )
    }

    private data class ReadyPreview(
        val session: PdfPreviewSession,
        val firstPage: RenderedPreview,
        val layoutApplied: Boolean,
        val printWarning: String?,
    )

    private fun cancelPendingPreview() {
        previewGeneration += 1
        previewTask?.cancel(true)
        previewTask = null
        activePreviewDialog?.dismiss()
        activePreviewDialog = null
    }

    private fun showPrintPreview() {
        if (printGate.isBusy()) {
            Toast.makeText(this, "인쇄 요청이 끝난 뒤 미리보기를 열어 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val printer = selected ?: run { Toast.makeText(this, "프린터를 선택하세요", Toast.LENGTH_SHORT).show(); return }
        val file = selectedFile
        val remote = remoteDocumentUri.text.toString().trim()
        if (file == null) {
            val message = if (remote.isNotBlank()) "원격 URL 문서는 프린터가 직접 가져오므로 앱에서 미리 볼 수 없습니다"
                else "미리 볼 파일을 선택하세요"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val settings = runCatching { filePreparationSettings(forPreview = true) }.getOrElse {
            Toast.makeText(this, it.message ?: "출력 설정을 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        val paperLabel = selectedPaperLabel()
        val marginInfo = previewMarginInfo(printer, settings.layout)
        cancelPendingPreview()
        val generation = previewGeneration
        updateStatus("미리보기 생성 중", StatusTone.ACTIVE)
        previewTask = io.submit {
            val result = runCatching {
                val document = filePreparer.preview(printer, file, settings)
                val session = try {
                    PdfPreviewSession.fromOwnedFile(document.pdfFile, document.colorMode, document.outputMime)
                } catch (error: Throwable) {
                    document.close()
                    throw error
                }
                try {
                    ReadyPreview(session, session.renderPage(0), document.layoutApplied, document.printWarning)
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
            main.post {
                val matchesInput = selectedFile == file && selected == printer &&
                    runCatching { filePreparationSettings(forPreview = true) == settings }.getOrDefault(false)
                if (generation != previewGeneration || isFinishing || isDestroyed || !matchesInput) {
                    result.getOrNull()?.let { ready ->
                        ready.firstPage.bitmap.recycle()
                        ready.session.close()
                    }
                    if (generation == previewGeneration && !isFinishing && !isDestroyed && !matchesInput) {
                        previewTask = null
                        updateStatus("문서나 설정이 바뀌어 미리보기를 취소했습니다. 다시 눌러 주세요.", StatusTone.NEUTRAL)
                    }
                    return@post
                }
                previewTask = null
                result.onSuccess { ready ->
                    updateStatus(if (ready.printWarning != null) "문서 미리보기 · 출력 설정 확인이 필요합니다"
                        else if (ready.layoutApplied) "전송할 문서의 배치 미리보기" else "원본 미리보기 · 배치는 프린터에서 처리합니다", StatusTone.SUCCESS)
                    showPreviewDialog(ready.session, ready.firstPage, paperLabel, marginInfo, ready.layoutApplied, ready.printWarning)
                }.onFailure {
                    updateStatus("미리보기 실패: ${it.message ?: it.javaClass.simpleName}", StatusTone.ERROR)
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class PreviewMarginInfo(
        val insets: PrintableAreaInsets,
        val description: String,
    )

    private fun advertisedPrinterMargins(printer: PrinterRecord?): List<Float>? =
        PrinterOutputPolicy.marginsMm(printer, selectedMedia(printer))

    private fun printerMarginSummary(printer: PrinterRecord): String {
        val margins = advertisedPrinterMargins(printer)
            ?: return "선택한 용지의 출력 가능 영역: 확인 불가"
        val (left, top, right, bottom) = margins
        return "출력 여백 광고 기반: 왼쪽 ${"%.1f".format(left)}mm · 위 ${"%.1f".format(top)}mm · " +
            "오른쪽 ${"%.1f".format(right)}mm · 아래 ${"%.1f".format(bottom)}mm"
    }

    private fun previewMarginInfo(printer: PrinterRecord, layout: ContentLayoutOptions): PreviewMarginInfo {
        val advertised = advertisedPrinterMargins(printer)
        val margins = advertised ?: listOf(0f, 0f, 0f, 0f)
        val (left, top, right, bottom) = margins
        val pageWidthMm = layout.pageWidth * 25.4f / 72f
        val pageHeightMm = layout.pageHeight * 25.4f / 72f
        val description = if (advertised == null) {
            "선택한 용지의 출력 가능 영역을 확인할 수 없어 붉은 영역을 표시하지 않습니다."
        } else {
            "IPP 광고 기반 예상 여백: 좌 ${"%.1f".format(left)} · 상 ${"%.1f".format(top)} · " +
                "우 ${"%.1f".format(right)} · 하 ${"%.1f".format(bottom)}mm"
        }
        return PreviewMarginInfo(
            insets = PrintableAreaInsets(
                leftRatio = left / pageWidthMm,
                topRatio = top / pageHeightMm,
                rightRatio = right / pageWidthMm,
                bottomRatio = bottom / pageHeightMm,
            ),
            description = description,
        )
    }

    private fun showPreviewDialog(
        session: PdfPreviewSession,
        firstPage: RenderedPreview,
        paperLabel: String,
        marginInfo: PreviewMarginInfo,
        layoutApplied: Boolean,
        printWarning: String?,
    ) {
        var currentPage = firstPage
        var loading = false
        var closed = false
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        val paperView = PaperPreviewView(this, firstPage.bitmap, if (layoutApplied) marginInfo.insets else null)
        container.addView(paperView, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageIndicator = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(ink)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val pageStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(danger)
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        lateinit var previousButton: Button
        lateinit var nextButton: Button
        fun updateNavigation() {
            previousButton.isEnabled = !closed && !loading && currentPage.pageIndex > 0
            nextButton.isEnabled = !closed && !loading && currentPage.pageIndex + 1 < currentPage.pageCount
            previousButton.alpha = if (previousButton.isEnabled) 1f else 0.4f
            nextButton.alpha = if (nextButton.isEnabled) 1f else 0.4f
            pageIndicator.text = "${currentPage.pageIndex + 1} / ${currentPage.pageCount}페이지" +
                if (loading) "\n불러오는 중…" else ""
        }
        fun releasePreview() {
            if (closed) return
            closed = true
            paperView.setPageBitmap(null)
            currentPage.bitmap.recycle()
            session.close()
        }
        fun loadPage(index: Int) {
            if (closed || loading || index !in 0 until currentPage.pageCount) return
            loading = true
            pageStatus.visibility = View.GONE
            updateNavigation()
            io.execute {
                val result = runCatching { session.renderPage(index) }
                main.post {
                    if (closed || isFinishing || isDestroyed) {
                        result.getOrNull()?.bitmap?.recycle()
                        releasePreview()
                        return@post
                    }
                    loading = false
                    result.onSuccess { next ->
                        val oldBitmap = currentPage.bitmap
                        paperView.setPageBitmap(next.bitmap)
                        currentPage = next
                        oldBitmap.recycle()
                    }.onFailure {
                        pageStatus.text = "페이지를 불러오지 못했습니다: ${it.message ?: it.javaClass.simpleName}"
                        pageStatus.visibility = View.VISIBLE
                    }
                    updateNavigation()
                }
            }
        }
        previousButton = button("이전") { loadPage(currentPage.pageIndex - 1) }
        nextButton = button("다음") { loadPage(currentPage.pageIndex + 1) }
        navigation.addView(previousButton)
        navigation.addView(pageIndicator, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(nextButton)
        container.addView(navigation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        container.addView(pageStatus)
        container.addView(TextView(this).apply {
            text = (if (layoutApplied) "흰색 테두리는 생성한 $paperLabel 페이지 경계입니다. 붉은 영역은 출력되지 않을 수 있습니다.\n${marginInfo.description}\n"
                else "문서 내용 확인용 화면이며 실제 출력 용지의 경계를 표시하지 않습니다. 크기·위치는 프린터 설정에 따라 달라질 수 있습니다.\n") +
                (if (printWarning != null) "출력 설정 확인: $printWarning"
                else if (layoutApplied) "표시한 용지·배치·페이지 선택을 문서에 적용해 전송합니다. 양면·급지함은 프린터에서 적용합니다."
                else "문서 페이지 미리보기입니다. 프린터가 적용하는 용지 배치는 화면과 다를 수 있습니다.") +
                "\n미리보기는 화면용 해상도이며 색상·압축 결과는 참고용입니다. 인쇄에는 원본 또는 선택한 변환 해상도를 사용합니다."
            textSize = 12f
            setTextColor(Color.rgb(80, 90, 105))
            setPadding(0, dp(8), 0, 0)
        })
        updateNavigation()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("출력 페이지 미리보기")
            .setView(container)
            .setPositiveButton("닫기", null)
            .create()
        dialog.setOnDismissListener {
            releasePreview()
            if (activePreviewDialog === dialog) activePreviewDialog = null
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        activePreviewDialog = dialog
        try {
            dialog.show()
        } catch (error: RuntimeException) {
            releasePreview()
            if (activePreviewDialog === dialog) activePreviewDialog = null
            updateStatus("미리보기 창을 열 수 없습니다: ${error.message}", StatusTone.ERROR)
        }
    }

    private fun sendOneJob(requestId: Long) {
        PrintWorkManager.submit(applicationContext, requestId)
    }

    private fun renderPrintWork(work: PrintWorkState) {
        if (isFinishing || isDestroyed) return
        fun showPrintStatus(message: String, tone: StatusTone) {
            updateStatus(message, tone)
            applyStatus(printStatus, message, tone)
        }
        val busy = printGate.isBusy()
        refreshJobStatusButton.visibility = if (work.phase == PrintWorkPhase.ACCEPTED &&
            !work.checkingStatus && work.jobStatus?.terminal != true) View.VISIBLE else View.GONE
        cancelPrintButton.visibility = if (work.phase == PrintWorkPhase.PREPARING) View.VISIBLE else View.GONE
        printButton.isEnabled = !busy
        printButton.alpha = if (busy) 0.65f else 1f
        printButton.text = when (work.phase) {
            PrintWorkPhase.WAITING_NAME -> "이름 설정 중"
            PrintWorkPhase.PREPARING -> "문서 준비 중"
            PrintWorkPhase.CANCELLING -> "취소 중"
            PrintWorkPhase.SENDING -> "전송 중"
            PrintWorkPhase.AUTH_REQUIRED -> "로그인 대기"
            else -> "인쇄하기"
        }
        when (work.phase) {
            PrintWorkPhase.WAITING_NAME -> if (activePrintDialog?.isShowing != true) {
                try {
                    printAfterSavingPrinter(work.request, work.requestId)
                } catch (error: RuntimeException) {
                    PrintWorkManager.failPending(work.requestId, "프린터 이름 창을 열 수 없습니다: ${error.message}")
                }
            }
            PrintWorkPhase.PREPARING -> showPrintStatus("출력 문서 준비 중", StatusTone.ACTIVE)
            PrintWorkPhase.CANCELLING -> showPrintStatus("문서 준비를 취소하고 임시 파일을 정리 중입니다", StatusTone.ACTIVE)
            PrintWorkPhase.SENDING -> showPrintStatus("${work.request.printer.endpoint.host}에 출력 요청 전송 중", StatusTone.ACTIVE)
            PrintWorkPhase.AUTH_REQUIRED -> {
                showPrintStatus("출력 전에 프린터 로그인이 필요합니다", StatusTone.ERROR)
                if (activePrintDialog?.isShowing != true) {
                    try {
                        showPrinterAuthDialog(
                            work.request.printer,
                            challengeOverride = work.result?.authChallenge,
                            onCancelled = { finishPrintRequest(work.requestId) },
                            onCredentialsReady = { sendOneJob(work.requestId) },
                        )
                    } catch (error: RuntimeException) {
                        PrintWorkManager.failPending(work.requestId, "프린터 로그인 창을 열 수 없습니다: ${error.message}")
                    }
                }
            }
            PrintWorkPhase.ACCEPTED -> {
                val result = work.result
                val job = work.jobStatus
                val message = buildString {
                    append("${work.request.printer.endpoint.host} · 작업 번호 ${result?.jobId ?: "-"}\n")
                    append(job?.label ?: "인쇄 요청을 접수했습니다")
                    job?.impressionsCompleted?.let { append(" · 완료 ${it}면") }
                    if (job?.state == 8 && job.reasons.isEmpty() && job.message == null) {
                        append("\n프린터가 중단 사유를 알려주지 않았습니다.")
                    }
                    job?.message?.let { append("\n$it") }
                    if (!job?.reasons.isNullOrEmpty()) append("\n${job!!.reasons.joinToString()}")
                    if (work.checkingStatus) append("\n출력 상태 확인 중")
                    work.statusError?.let { append("\n$it\n출력 실패로 확정된 것은 아닙니다. 프린터에서 확인하세요.") }
                    result?.warning?.let { append("\n$it") }
                }
                showPrintStatus(message, when {
                    job?.hasError == true || work.statusError != null || result?.warning != null -> StatusTone.ERROR
                    job?.state == 9 -> StatusTone.SUCCESS
                    else -> StatusTone.ACTIVE
                })
            }
            PrintWorkPhase.UNKNOWN -> showPrintStatus(work.result?.error ?: "접수 여부를 확인할 수 없습니다. 프린터에서 출력 여부를 확인하세요.", StatusTone.ERROR)
            PrintWorkPhase.FAILED -> {
                val error = work.result?.error ?: "인쇄 요청을 처리할 수 없습니다"
                showPrintStatus(if (error.startsWith("전송 실패:")) error else "출력 요청 실패: $error", StatusTone.ERROR)
            }
            PrintWorkPhase.CANCELLED -> showPrintStatus("인쇄 요청을 취소했습니다", StatusTone.NEUTRAL)
        }
    }

    private fun selectedContentLayout(requirePaper: Boolean = true): ContentLayoutOptions {
        val orientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition) ?: selected?.defaultOrientation
        val media = selectedMedia(selected)
        val size = PrinterOutputPolicy.pageSize(selected, media)
        require(!requirePaper || size != null) { "선택 용지의 크기를 확인할 수 없습니다. 크기가 표시된 용지를 선택하세요." }
        val (pageWidth, pageHeight) = size ?: (1 to 1)
        val ippX = xPositionValues.getOrNull(xPositionSpinner.selectedItemPosition)?.lowercase()
        val ippY = yPositionValues.getOrNull(yPositionSpinner.selectedItemPosition)?.lowercase()
        val ippScaling = printScalingValues.getOrNull(printScalingSpinner.selectedItemPosition)?.lowercase()
        val scalePercent = contentScalePercentInput.text.toString().trim().let {
            if (it.isBlank()) 100f else it.toFloatOrNull() ?: error("내용 크기를 숫자로 입력하세요")
        }
        val offsetXmm = contentOffsetXInput.text.toString().trim().let {
            if (it.isBlank()) 0f else it.toFloatOrNull() ?: error("가로 이동값을 숫자로 입력하세요")
        }
        val offsetYmm = contentOffsetYInput.text.toString().trim().let {
            if (it.isBlank()) 0f else it.toFloatOrNull() ?: error("세로 이동값을 숫자로 입력하세요")
        }
        val advertisedMargins = advertisedPrinterMargins(selected)
        val usePrinterMargins = contentMarginValues.getOrNull(contentMarginSpinner.selectedItemPosition) == -1
        require(!requirePaper || !(usePrinterMargins || contentScaleSpinner.selectedItemPosition == 4) || advertisedMargins != null) {
            "선택 용지의 광고 여백이 없어 출력 가능 영역을 계산할 수 없습니다. 다른 여백 또는 배치를 선택하세요."
        }
        val printableMarginsMm = advertisedMargins ?: listOf(0f, 0f, 0f, 0f)
        val printableMarginsPt = printableMarginsMm.map { (it * 72f / 25.4f).toInt().coerceAtLeast(0) }
        require(scalePercent in 10f..400f) { "내용 크기는 10~400%로 입력하세요" }
        require(offsetXmm in -300f..300f && offsetYmm in -300f..300f) { "이동값은 -300~300mm로 입력하세요" }
        val sourceMime = selectedFileMime?.let(PrintFormatSupport::normalize)
        val printer = selected
        val directPdf = sourceMime == PrintFormatSupport.PDF && printer != null &&
            PrintFormatSupport.supports(printer.formats, sourceMime)
        val conversionMime = if (sourceMime == null || printer == null) null else runCatching {
            DocumentOutputPlanner.plan(
                printer.formats,
                sourceMime,
                PrintCapabilityPolicy.usesAppLayout(sourceMime, hasExplicitLayoutRequest()),
                false,
                colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition) ?: printer.defaultColorMode,
                if (directPdf) null else selectedConversionMime(),
                printer = printer,
                sidesMode = sidesValues.getOrNull(sidesSpinner.selectedItemPosition) ?: printer.defaultSides,
            ).mime.let(PrintFormatSupport::normalize)
        }.getOrNull()
        val dpiChoice = rasterDpiValues.getOrNull(rasterDpiSpinner.selectedItemPosition)
        val preservePhotoPixelsForPdf = conversionMime == PrintFormatSupport.PDF &&
            PrintCapabilityPolicy.isPhoto(sourceMime)
        val manualDpi = dpiChoice?.takeIf { it > 0 }
        val automaticDpi = printer?.let { AutomaticRasterPolicy.outputDpi(it, conversionMime) }
        val jpegQuality = when (conversionMime) {
            PrintFormatSupport.POSTSCRIPT -> when (psEncodingSpinner.selectedItemPosition) { 1 -> 100; 2 -> 90; else -> null }
            "image/jpeg" -> if (psEncodingSpinner.selectedItemPosition == 1) 90 else 100
            else -> null
        }
        return ContentLayoutOptions(
            horizontal = when (ippX) {
                "left" -> ContentHorizontal.LEFT
                "right" -> ContentHorizontal.RIGHT
                "center" -> ContentHorizontal.CENTER
                else -> ContentHorizontal.entries.getOrElse(contentHorizontalSpinner.selectedItemPosition) { ContentHorizontal.CENTER }
            },
            vertical = when (ippY) {
                "top" -> ContentVertical.TOP
                "bottom" -> ContentVertical.BOTTOM
                "center" -> ContentVertical.CENTER
                else -> ContentVertical.entries.getOrElse(contentVerticalSpinner.selectedItemPosition) { ContentVertical.CENTER }
            },
            scale = when (ippScaling) {
                "fill" -> ContentScale.FILL
                "none" -> ContentScale.ACTUAL
                "auto-fit" -> ContentScale.SHRINK_ONLY
                "fit", "auto" -> ContentScale.FIT
                else -> when (contentScaleSpinner.selectedItemPosition) {
                    2 -> ContentScale.FILL
                    3 -> ContentScale.FULL_BLEED_FILL
                    4 -> ContentScale.PRINTABLE_FIT
                    5 -> ContentScale.ACTUAL
                    6 -> ContentScale.SHRINK_ONLY
                    else -> ContentScale.FIT
                }
            },
            margin = contentMarginValues.getOrElse(contentMarginSpinner.selectedItemPosition) { 0 }.coerceAtLeast(0),
            usePrinterMargins = usePrinterMargins,
            rasterDpi = when {
                preservePhotoPixelsForPdf -> -1
                else -> manualDpi ?: automaticDpi ?: 0
            },
            postScriptJpegQuality = jpegQuality,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            scalePercent = scalePercent,
            offsetX = offsetXmm * 72f / 25.4f,
            offsetY = offsetYmm * 72f / 25.4f,
            rotationDegrees = PrintLayoutSupport.contentRotationDegrees(orientation),
            printableLeft = printableMarginsPt[0],
            printableTop = printableMarginsPt[1],
            printableRight = printableMarginsPt[2],
            printableBottom = printableMarginsPt[3],
        )
    }

    private fun selectedPaperLabel(): String {
        val media = selectedMedia(selected)
        val base = media?.let(::mediaLabel) ?: "크기 미확인"
        val orientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition) ?: selected?.defaultOrientation
        return when (orientation) {
            4 -> "$base · 용지 고정 · 내용 90° 회전"
            5 -> "$base · 용지 고정 · 내용 270° 회전"
            6 -> "$base · 용지 고정 · 내용 180° 회전"
            else -> "$base · 용지 고정 · 내용 세로"
        }
    }

    private fun selectedCopies(printer: PrinterRecord): Int {
        val raw = copiesInput.text.toString().trim()
        val value = if (raw.isBlank()) 1 else raw.toIntOrNull() ?: error("복사 매수를 올바른 정수로 입력하세요")
        val supported = printer.copiesSupported
        require(value >= 1) { "복사 매수는 1 이상이어야 합니다" }
        if (supported != null) require(value in supported.lower..supported.upper) {
            "이 프린터의 복사 매수 범위는 ${supported.lower}~${supported.upper}부입니다"
        }
        return value
    }

    private fun selectedPageRanges(): List<IppRange> {
        if (!pageRangesInput.isEnabled) return emptyList()
        val value = pageRangesInput.text.toString().trim()
        if (value.isBlank()) return emptyList()
        return value.split(',').map { token ->
            val parts = token.trim().split('-', limit = 2)
            val lower = parts[0].trim().toIntOrNull() ?: error("페이지 범위 형식은 1-3,5처럼 입력하세요")
            val upper = if (parts.size == 2) parts[1].trim().toIntOrNull()
                ?: error("페이지 범위 형식은 1-3,5처럼 입력하세요") else lower
            require(lower >= 1 && upper >= lower) { "페이지 범위를 확인하세요: $token" }
            IppRange(lower, upper)
        }
    }

    private fun fileDisplayName(uri: Uri): String = filePreparer.fileDisplayName(uri)

    private fun requestNearbyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 51)
        }
    }

    private fun editorTextFields(): Map<String, EditText> = linkedMapOf(
        "address" to directPrinterAddress, "cidr" to cidr, "hostCap" to hostCap,
        "search" to printerSearch, "url" to remoteDocumentUri, "copies" to copiesInput,
        "ranges" to pageRangesInput,
        "scalePercent" to contentScalePercentInput, "offsetX" to contentOffsetXInput,
        "offsetY" to contentOffsetYInput,
    )

    private fun editorSpinners(): Map<String, Spinner> = linkedMapOf(
        "submission" to submissionMethodSpinner, "color" to colorModeSpinner, "media" to mediaSpinner,
        "scaling" to printScalingSpinner, "sides" to sidesSpinner, "orientation" to orientationSpinner,
        "quality" to printQualitySpinner, "resolution" to resolutionSpinner, "numberUp" to numberUpSpinner,
        "xPosition" to xPositionSpinner, "yPosition" to yPositionSpinner,
        "horizontal" to contentHorizontalSpinner, "vertical" to contentVerticalSpinner,
        "contentScale" to contentScaleSpinner, "margin" to contentMarginSpinner,
        "conversionFormat" to conversionFormatSpinner,
        "conversionDpiV3" to rasterDpiSpinner, "conversionEncodingV3" to psEncodingSpinner,
    )

    private fun optionKeys(key: String): List<String>? {
        val values: List<Any?> = when (key) {
            "submission" -> submissionMethodValues
            "color" -> colorModeValues
            "media" -> mediaValues
            "scaling" -> printScalingValues
            "sides" -> sidesValues
            "orientation" -> orientationValues
            "quality" -> printQualityValues
            "resolution" -> resolutionValues
            "numberUp" -> numberUpValues
            "xPosition" -> xPositionValues
            "yPosition" -> yPositionValues
            "margin" -> contentMarginValues
            "conversionFormat" -> conversionTargetMimes.map { it?.let(PrintFormatSupport::normalize) }
            "conversionDpiV3" -> rasterDpiValues
            "conversionEncodingV3" -> if (selectedConversionMime()?.let(PrintFormatSupport::normalize) == "image/jpeg")
                listOf("100", "90") else listOf("lossless", "100", "90")
            else -> return null
        }
        return values.map { it?.toString() ?: "default" }
    }

    // Only editor data is saved. Passwords, rendered pages and file contents stay out of the Bundle.
    private fun saveEditorState(): Bundle = Bundle().apply {
        putSerializable("printer", selected)
        putString("file", selectedFile?.toString())
        putString("fileMime", selectedFileMime)
        putString("fileLabel", fileLabel.text.toString())
        editorTextFields().forEach { (key, field) -> putString("text:$key", field.text.toString()) }
        editorSpinners().forEach { (key, spinner) ->
            putInt("spinner:$key", spinner.selectedItemPosition)
            putString("choice:$key", optionKeys(key)?.getOrNull(spinner.selectedItemPosition))
        }
        expandablePanels.forEach { (key, views) -> putBoolean("panel:$key", views.first.visibility == View.VISIBLE) }
        putInt("scrollY", mainScroll.scrollY)
        val work = PrintWorkManager.state
        if (work?.phase == PrintWorkPhase.WAITING_NAME) {
            putLong("nameRequestId", work.requestId)
            putString("nameDraft", activePrintNameInput?.text?.toString() ?: pendingPrinterAlias)
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreEditorState(editor: Bundle?) {
        restoringEditor = true
        try {
            val activeWork = PrintWorkManager.state?.takeIf { printGate.isActive(it.requestId) }
            selected = (editor?.getSerializable("printer") as? PrinterRecord) ?: activeWork?.request?.printer
            selectedFile = editor?.getString("file")?.let(Uri::parse) ?: if (editor == null) activeWork?.request?.file else null
            selectedFileMime = editor?.getString("fileMime")
            selected?.let { printer ->
                upsertPrinter(printer)
                showSelectedPrinter(printer)
            }
            editorTextFields().forEach { (key, field) ->
                editor?.getString("text:$key")?.let(field::setText)
            }
            if (editor == null) activeWork?.request?.remoteUri?.let(remoteDocumentUri::setText)
            fileLabel.text = editor?.getString("fileLabel") ?: selectedFile?.let {
                runCatching { fileDisplayName(it) }.getOrDefault("선택한 파일")
            } ?: "선택한 파일이 없습니다"
            configurePageControls(selected)
            fun restoreChoice(key: String, spinner: Spinner) {
                val keys = optionKeys(key)
                val saved = editor?.getString("choice:$key")
                if (keys != null && saved != null) {
                    spinner.setSelection(keys.indexOf(saved).takeIf { it >= 0 } ?: 0)
                } else if (keys == null && editor?.containsKey("spinner:$key") == true) {
                    spinner.setSelection(editor.getInt("spinner:$key").coerceIn(0, (spinner.count - 1).coerceAtLeast(0)))
                }
            }
            editorSpinners().filterKeys { !it.startsWith("conversion") }.forEach { (key, spinner) -> restoreChoice(key, spinner) }
            setContentMarginOptions(selected, preserve = true)
            updateConversionUi(selected)
            restoreChoice("conversionFormat", conversionFormatSpinner)
            updateConversionUi(selected)
            restoreChoice("conversionDpiV3", rasterDpiSpinner)
            restoreChoice("conversionEncodingV3", psEncodingSpinner)
            updateConversionUi(selected)
            expandablePanels.forEach { (key, views) ->
                if (editor?.containsKey("panel:$key") == true) {
                    val open = editor.getBoolean("panel:$key")
                    views.first.visibility = if (open) View.VISIBLE else View.GONE
                    views.second.text = "$key  ${if (open) "−" else "＋"}"
                }
            }
            updateSavedPrintersExpander()
            if (activeWork?.requestId == editor?.getLong("nameRequestId")) {
                pendingPrinterAlias = editor?.getString("nameDraft")
            }
            updateDocumentInputAvailability()
        } finally {
            restoringEditor = false
        }
        configurePageControls(selected)
        selectedFile?.let { inspectSelectedDocument() }
        val scrollY = editor?.getInt("scrollY") ?: 0
        mainScroll.post { if (!isDestroyed) mainScroll.scrollTo(0, scrollY) }
    }

    override fun onStop() {
        retainedEditorState = saveEditorState()
        PrintWorkManager.removeObserver(printObserverOwner)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveEditorState().let { editor ->
            retainedEditorState = editor
            outState.putBundle(STATE_EDITOR, editor)
        }
        outState.putBoolean(STATE_PRINT_WAS_ACTIVE, printGate.isBusy())
        lastBgpScope?.let { scope ->
            outState.putString(STATE_BGP_EGRESS, scope.egressIp)
            outState.putString(STATE_BGP_PREFIX, scope.prefix)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        recreatingUi = !isFinishing
        PrintWorkManager.removeObserver(printObserverOwner)
        activePrintDialog?.dismiss()
        activePrintDialog = null
        cancelPendingPreview()
        mdnsFinishRunnable?.let(main::removeCallbacks)
        mdnsCountdownRunnable?.let(main::removeCallbacks)
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        discovery.close()
        io.shutdownNow()
        if (isFinishing && !printGate.isBusy()) IppClient.clearAllCredentials()
        super.onDestroy()
    }

    companion object {
        private const val MAX_SAVED_PRINTERS = 20
        private const val PREFS_NAME = "ipp_printer_connect"
        private const val PREF_SAVED_PRINTERS = "saved_printers_json"
        private const val PREF_LAST_PRINTED_PRINTER = "last_printed_printer_uri"
        private const val STATE_BGP_EGRESS = "bgp-egress"
        private const val STATE_BGP_PREFIX = "bgp-prefix"
        private const val STATE_EDITOR = "editor"
        private const val STATE_PRINT_WAS_ACTIVE = "print-was-active"
        private var retainedEditorState: Bundle? = null
    }
}
