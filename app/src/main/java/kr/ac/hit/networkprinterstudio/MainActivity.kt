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
import android.provider.OpenableColumns
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var discovery: PrinterDiscovery
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
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
    private lateinit var contentTextSizeSpinner: Spinner
    private lateinit var contentScalePercentInput: EditText
    private lateinit var contentOffsetXInput: EditText
    private lateinit var contentOffsetYInput: EditText
    private lateinit var applyLayoutToPdf: CheckBox
    private lateinit var forcePostScript: CheckBox
    private lateinit var textBody: EditText
    private lateinit var remoteDocumentUri: EditText
    private lateinit var fileLabel: TextView
    private lateinit var authorized: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
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
        startNetworkMonitoring()
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
        networkCard.addView(savedPrintersPanel)
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
        resolutionSpinner = addSpinnerRow(advancedPrintSettings, "해상도")
        numberUpSpinner = addSpinnerRow(advancedPrintSettings, "한 장당 페이지")
        xPositionSpinner = addSpinnerRow(advancedPrintSettings, "가로 위치")
        yPositionSpinner = addSpinnerRow(advancedPrintSettings, "세로 위치")
        copiesInput = addTextOptionRow(advancedPrintSettings, "복사 매수", "1").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        pageRangesInput = addTextOptionRow(advancedPrintSettings, "페이지 범위", "예: 1-3,5")
        setSubmissionMethodOptions(null)
        setColorModeOptions(null)
        setMediaOptions(null)
        setPrintScalingOptions(null)
        setExtendedPrintOptions(null)
        val documentCard = section("문서", "", "사진이나 PDF를 고르면 형식을 자동으로 맞춥니다")
        documentCard.addView(button("사진 또는 문서 선택", primary = true) { chooseFile() })
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
        fileRow.addView(button("지우기") { selectedFile = null; fileLabel.text = "선택한 파일이 없습니다" })
        documentCard.addView(fileRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(16) })
        documentCard.addView(fieldLabel("또는 직접 텍스트 입력"))
        textBody = EditText(this).apply {
            hint = "출력할 내용을 입력하세요"
            minLines = 3
            gravity = 48
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(Color.rgb(152, 162, 179))
            background = roundedBackground(Color.rgb(249, 250, 251), line, dp(12).toFloat())
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        documentCard.addView(textBody)
        forcePostScript = CheckBox(this).apply {
            text = "현재 문서를 PostScript로 변환하여 전송"
            textSize = 13f
            setTextColor(ink)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(0, dp(12), 0, 0)
        }
        documentCard.addView(forcePostScript)
        documentCard.addView(TextView(this).apply {
            text = "선택한 PDF·사진·일반 텍스트 파일 또는 위에 직접 입력한 텍스트를 PostScript로 변환합니다. " +
                "프린터가 application/postscript 형식을 지원할 때 사용할 수 있습니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(5), dp(2), dp(12))
        })
        documentCard.addView(fieldLabel("또는 프린터가 직접 가져올 문서 URL"))
        remoteDocumentUri = EditText(this).apply {
            hint = "문서 URL (프린터가 직접 다운로드)"
            setSingleLine()
            styleInput()
        }
        documentCard.addView(TextView(this).apply {
            text = "Print-URI/Send-URI 지원 프린터에 URL만 전달합니다. 프린터가 원본을 직접 받으므로 앱의 PostScript 변환과 페이지 배치는 적용되지 않습니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        documentCard.addView(remoteDocumentUri, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
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
                "페이지에 맞춤",
                "페이지 채우기",
                "페이지 전부 채우기 (넘치는 부분 잘림)",
                "페이지 채우기 (프린트 불가 영역 제외)",
                "원본 픽셀 크기",
            ),
            0,
        )
        layoutSettings.addView(TextView(this).apply {
            text = "페이지에 맞춤: 전체 내용이 보이도록 설정한 내부 여백 안에 맞춥니다.\n" +
                "페이지 채우기: 설정한 내부 여백 안쪽을 채우며 넘친 부분은 잘릴 수 있습니다.\n" +
                "페이지 전부 채우기: 비율을 유지한 채 A4 전체를 덮고 넘친 부분을 자릅니다.\n" +
                "프린트 불가 영역 제외: 전체 내용이 출력 가능 영역 안에 들어오도록 맞춰 잘리지 않게 합니다.\n" +
                "원본 픽셀 크기: 확대·축소 없이 원본 픽셀 크기로 배치합니다.\n" +
                "※ IPP 여백 정보가 없으면 프린트 불가 영역을 가장자리 5mm로 예상합니다."
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
        contentTextSizeSpinner = addFixedSpinnerRow(layoutSettings, "텍스트 크기", listOf("작게", "보통", "크게"), 1)
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
        applyLayoutToPdf = CheckBox(this).apply {
            text = "PDF도 미리보기와 같은 용지·배치로 변환"
            textSize = 13f
            isChecked = true
            isEnabled = false
            buttonTintList = ColorStateList.valueOf(accent)
        }
        layoutSettings.addView(applyLayoutToPdf)
        authorized = CheckBox(this).apply {
            text = "이 프린터로 출력하는 것을 확인했습니다"
            textSize = 13f
            setTextColor(ink)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(0, dp(8), 0, dp(8))
        }
        val finalActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        finalActions.addView(button("미리보기") { showPrintPreview() }, LinearLayout.LayoutParams(0, -2, 1f))
        finalActions.addView(button("인쇄하기", primary = true) { validateAndPrint() }, LinearLayout.LayoutParams(0, -2, 1.35f).apply { marginStart = dp(8) })

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
        detailCard.addView(authorized)
        detailCard.addView(finalActions)
        addSection(root, detailCard)

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

    private fun printAfterSavingPrinter(printer: PrinterRecord) {
        if (savedPrinters.any { it.endpoint.key == printer.endpoint.key }) {
            rememberPrintedPrinter(printer)
            sendOneJob(printer)
            return
        }
        val suggestedName = suggestedPrinterName(printer)
        val input = EditText(this).apply {
            hint = "예: 우리집 프린터"
            setText(suggestedName)
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
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("프린터 이름 설정")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장 후 인쇄", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "저장할 프린터 이름을 입력하세요"
                    return@setOnClickListener
                }
                savedPrinterAliases[printer.endpoint.key] = name.take(80)
                rememberPrintedPrinter(printer)
                dialog.dismiss()
                sendOneJob(printer)
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
            background = roundedBackground(
                if (isSelected) accentSoft else Color.rgb(249, 250, 251),
                if (isSelected) accent else line,
                dp(13).toFloat(),
            ).also { if (isSelected) it.setStroke(dp(2), accent) }
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

    private fun ensurePrintAuthorized(): Boolean {
        if (authorized.isChecked) return true
        Toast.makeText(this, "실제 출력 전에 출력 권한 확인을 체크하세요", Toast.LENGTH_SHORT).show(); return false
    }

    private fun showPrinterAuthDialog(
        printer: PrinterRecord,
        challengeOverride: IppAuthChallenge? = null,
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

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("IPP 프린터 로그인")
            .setView(container)
            .setPositiveButton("로그인", null)
            .setNegativeButton("취소", null)
            .setNeutralButton("인증 삭제", null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = !unsupportedKnownScheme
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = IppClient.hasCredentials(endpoint)
                setOnClickListener {
                    IppClient.clearCredentials(endpoint)
                    updateStatus("${endpoint.host}의 저장된 인증 정보를 삭제했습니다", StatusTone.NEUTRAL)
                    if (printer.available) showSelectedPrinter(printer)
                    dialog.dismiss()
                }
            }
            positive.setOnClickListener {
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
                    showSelectedPrinter(printer)
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
        printerAuthButton.visibility = View.VISIBLE
        printerAuthButton.text = if (IppClient.hasCredentials(printer.endpoint)) {
            "프린터 로그인 변경 / 삭제"
        } else {
            "프린터 로그인 / 자격 증명"
        }
        printerAuthButton.setOnClickListener { showPrinterAuthDialog(printer) }
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

    private fun setContentMarginOptions(printer: PrinterRecord?) {
        if (!::contentMarginSpinner.isInitialized) return
        val advertised = advertisedPrinterMargins(printer)
        if (advertised == null) {
            contentMarginValues = listOf(0, 20, 43, 77)
            contentMarginSpinner.adapter = scrollableSpinnerAdapter(
                listOf("없음 (0mm)", "좁게 (약 7mm)", "보통 (약 15mm)", "넓게 (약 27mm)"),
            )
            contentMarginSpinner.setSelection(2)
            contentMarginDetailLabel.visibility = View.GONE
            return
        }
        val (left, top, right, bottom) = advertised
        val safeMm = advertised.maxOrNull() ?: 0f
        val safePoints = (safeMm * 72f / 25.4f).toInt().coerceAtLeast(0)
        contentMarginValues = listOf(safePoints, 0, 20, 43, 77)
        contentMarginSpinner.adapter = scrollableSpinnerAdapter(
            listOf(
                "프린터 광고 여백 사용",
                "없음 (0mm)",
                "좁게 (약 7mm)",
                "보통 (약 15mm)",
                "넓게 (약 27mm)",
            ),
        )
        contentMarginSpinner.setSelection(0)
        contentMarginDetailLabel.text =
            "프린터 광고값: 왼쪽 ${"%.1f".format(left)}mm · 위 ${"%.1f".format(top)}mm\n" +
                "오른쪽 ${"%.1f".format(right)}mm · 아래 ${"%.1f".format(bottom)}mm"
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
        val defaultLabel = printer?.defaultMedia?.let { "프린터 기본값 (${mediaLabel(it)})" } ?: "프린터 기본값"
        mediaSpinner.adapter = scrollableSpinnerAdapter(listOf(defaultLabel) + values.map(::mediaLabel))
        mediaSpinner.isEnabled = values.isNotEmpty()
    }

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
        numberUpValues = configureOptions(numberUpSpinner, printer?.numberUps.orEmpty(), printer?.defaultNumberUp) { "${it}페이지" }
        xPositionValues = configureOptions(xPositionSpinner, printer?.xImagePositions.orEmpty(), printer?.defaultXImagePosition, ::xPositionLabel)
        yPositionValues = configureOptions(yPositionSpinner, printer?.yImagePositions.orEmpty(), printer?.defaultYImagePosition, ::yPositionLabel)
        val copies = printer?.copiesSupported
        copiesInput.setText("1")
        copiesInput.hint = copies?.let { "${it.lower}~${it.upper}부" } ?: "1"
        copiesInput.isEnabled = copies != null && copies.upper > 1
        pageRangesInput.setText("")
        pageRangesInput.isEnabled = printer?.pageRangesSupported == true
        pageRangesInput.hint = if (printer?.pageRangesSupported == true) "예: 1-3,5" else "프린터 미지원"
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
            selectedFile = data?.data
            selectedFile?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                fileLabel.text = fileDisplayName(uri)
            }
        }
    }

    private fun validateAndPrint() {
        if (!ensurePrintAuthorized()) return
        val printer = selected ?: run { Toast.makeText(this, "프린터를 선택하세요", Toast.LENGTH_SHORT).show(); return }
        if (!printer.supportsPrintJob && !printer.supportsCreateSendDocument) {
            Toast.makeText(this, "이 프린터는 Print-Job 또는 Create-Job/Send-Document 지원을 광고하지 않습니다", Toast.LENGTH_LONG).show()
            return
        }
        if (selectedFile == null && remoteDocumentUri.text.isBlank() && textBody.text.isBlank()) {
            Toast.makeText(this, "텍스트, 파일 또는 문서 URL을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedFile == null && remoteDocumentUri.text.isNotBlank()) {
            val remote = runCatching { java.net.URI(remoteDocumentUri.text.toString().trim()) }.getOrNull()
            if (remote?.scheme?.lowercase() !in setOf("http", "https") || remote?.host.isNullOrBlank()) {
                Toast.makeText(this, "문서 URL은 올바른 HTTP 또는 HTTPS 주소여야 합니다", Toast.LENGTH_LONG).show()
                return
            }
            if (forcePostScript.isChecked) {
                Toast.makeText(
                    this,
                    "문서 URL은 프린터가 직접 다운로드하므로 앱에서 PostScript로 변환할 수 없습니다. PostScript 변환을 끄거나 파일을 직접 선택하세요.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }
        val optionError = runCatching {
            selectedCopies(printer)
            selectedPageRanges()
            selectedContentLayout()
        }.exceptionOrNull()
        if (optionError != null) {
            Toast.makeText(this, optionError.message ?: "출력 옵션을 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        printAfterSavingPrinter(printer)
    }

    private fun showPrintPreview() {
        val file = selectedFile
        val text = textBody.text.toString()
        val remote = remoteDocumentUri.text.toString().trim()
        if (file == null && text.isBlank()) {
            val message = if (remote.isNotBlank()) "원격 URL 문서는 프린터가 직접 가져오므로 앱에서 미리 볼 수 없습니다"
                else "미리 볼 텍스트나 파일을 선택하세요"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val layout = runCatching { selectedContentLayout() }.getOrElse {
            Toast.makeText(this, it.message ?: "레이아웃 값을 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        val paperLabel = selectedPaperLabel()
        val layoutPdf = applyLayoutToPdf.isChecked
        val pageRanges = runCatching { selectedPageRanges() }.getOrElse {
            Toast.makeText(this, it.message ?: "페이지 범위를 확인하세요", Toast.LENGTH_LONG).show()
            return
        }
        val numberUp = numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition) ?: 1
        updateStatus("미리보기 생성 중", StatusTone.ACTIVE)
        io.execute {
            val result = runCatching {
                val previewPdf = if (file == null) {
                    DocumentRenderer.textPdf("", text, layout)
                } else {
                    val name = fileDisplayName(file)
                    val bytes = readFileLimited(file, MAX_FILE_BYTES)
                    when (detectMime(file, name, bytes)) {
                        "application/pdf" -> if (layoutPdf || layout.rotationDegrees != 0f) {
                            contentResolver.openFileDescriptor(file, "r")?.use { descriptor ->
                                DocumentRenderer.pdfWithLayout(descriptor, layout)
                            } ?: error("PDF 파일을 열 수 없습니다")
                        } else bytes
                        "text/plain" -> DocumentRenderer.textPdf(name, bytes.toString(Charsets.UTF_8), layout)
                        "image/jpeg", "image/png", "image/tiff" -> DocumentRenderer.imagePdf(bytes, layout)
                        else -> error("이 파일 형식은 앱에서 미리 볼 수 없습니다")
                    }
                }
                val finalPreviewPdf = applySheetOptions(previewPdf, pageRanges, numberUp, layout)
                renderPreviewBytes(finalPreviewPdf, 0, null)
            }
            main.post {
                result.onSuccess { preview ->
                    updateStatus("미리보기 준비됨", StatusTone.SUCCESS)
                    showPreviewDialog(preview, paperLabel, layout)
                }.onFailure {
                    updateStatus("미리보기 실패: ${it.message ?: it.javaClass.simpleName}", StatusTone.ERROR)
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderPreviewBytes(bytes: ByteArray, pageIndex: Int, orientation: Int?): RenderedPreview {
        val temp = java.io.File.createTempFile("ipp-preview-", ".pdf", cacheDir)
        return try {
            temp.outputStream().use { it.write(bytes) }
            android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                DocumentRenderer.pdfPagePreview(descriptor, pageIndex, orientation)
            }
        } finally {
            temp.delete()
        }
    }

    private fun applySheetOptions(
        bytes: ByteArray,
        pageRanges: List<IppRange>,
        numberUp: Int,
        layout: ContentLayoutOptions,
    ): ByteArray {
        if (pageRanges.isEmpty() && numberUp <= 1) return bytes
        val temp = java.io.File.createTempFile("ipp-sheet-source-", ".pdf", cacheDir)
        return try {
            temp.outputStream().use { it.write(bytes) }
            android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                DocumentRenderer.imposePdf(descriptor, pageRanges, numberUp, layout.pageWidth, layout.pageHeight)
            }
        } finally {
            temp.delete()
        }
    }

    private data class PreviewMarginInfo(
        val insets: PrintableAreaInsets,
        val description: String,
    )

    private fun advertisedMarginMm(values: List<Int>): Float? {
        val valid = values.filter { it >= 0 }
        if (valid.isEmpty()) return null
        // A zero value can belong only to special borderless media. If the same
        // printer also advertises a positive value, use the positive minimum as
        // the safer expectation for ordinary paper such as A4.
        val hundredthsMm = valid.filter { it > 0 }.minOrNull() ?: valid.minOrNull() ?: return null
        return hundredthsMm / 100f
    }

    private fun advertisedPrinterMargins(printer: PrinterRecord?): List<Float>? {
        printer ?: return null
        val margins = listOf(
            advertisedMarginMm(printer.mediaLeftMargins),
            advertisedMarginMm(printer.mediaTopMargins),
            advertisedMarginMm(printer.mediaRightMargins),
            advertisedMarginMm(printer.mediaBottomMargins),
        )
        return margins.takeIf { values -> values.all { it != null } }?.map { it!! }
    }

    private fun printerMarginSummary(printer: PrinterRecord): String {
        val margins = advertisedPrinterMargins(printer)
            ?: return "출력 여백 광고: 없음 · 미리보기에서는 가장자리 5mm를 예상 표시"
        val (left, top, right, bottom) = margins
        return "출력 여백 광고 기반: 왼쪽 ${"%.1f".format(left)}mm · 위 ${"%.1f".format(top)}mm · " +
            "오른쪽 ${"%.1f".format(right)}mm · 아래 ${"%.1f".format(bottom)}mm"
    }

    private fun previewMarginInfo(layout: ContentLayoutOptions): PreviewMarginInfo {
        val advertised = advertisedPrinterMargins(selected)
        val margins = advertised ?: listOf(5f, 5f, 5f, 5f)
        val (left, top, right, bottom) = margins
        val pageWidthMm = layout.pageWidth * 25.4f / 72f
        val pageHeightMm = layout.pageHeight * 25.4f / 72f
        val description = if (advertised == null) {
            "프린터가 물리적 여백을 광고하지 않아 가장자리 5mm를 예상한 표시입니다."
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
        preview: RenderedPreview,
        paperLabel: String,
        layout: ContentLayoutOptions,
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        val marginInfo = previewMarginInfo(layout)
        container.addView(
            PaperPreviewView(this, preview.bitmap, marginInfo.insets),
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        container.addView(TextView(this).apply {
            text = "흰색 테두리는 실제 $paperLabel 용지 경계입니다. 붉은 영역은 출력되지 않을 수 있습니다.\n" +
                "${marginInfo.description}\n" +
                "${preview.pageIndex + 1}/${preview.pageCount}페이지 · 양면, 한 장당 페이지 수, 급지함 등은 프린터에서 적용됩니다"
            textSize = 12f
            setTextColor(Color.rgb(80, 90, 105))
            setPadding(0, dp(8), 0, 0)
        })
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("출력 페이지 미리보기")
            .setView(container)
            .setPositiveButton("닫기", null)
            .create()
        dialog.setOnDismissListener { preview.bitmap.recycle() }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        dialog.show()
    }

    private fun sendOneJob(printer: PrinterRecord) {
        hideCidrProgress()
        updateStatus("${printer.endpoint.host}에 1회 전송 중", StatusTone.ACTIVE)
        val colorMode = colorModeValues.getOrNull(colorModeSpinner.selectedItemPosition)
        val media = mediaValues.getOrNull(mediaSpinner.selectedItemPosition)
        val requestedPrintScaling = printScalingValues.getOrNull(printScalingSpinner.selectedItemPosition)
        val requestedOrientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition)
        val requestedXPosition = xPositionValues.getOrNull(xPositionSpinner.selectedItemPosition)
        val requestedYPosition = yPositionValues.getOrNull(yPositionSpinner.selectedItemPosition)
        val remoteUri = remoteDocumentUri.text.toString().trim().takeIf { selectedFile == null && it.isNotBlank() }
        val layoutBakedIntoDocument = remoteUri == null
        val requestedPageRanges = selectedPageRanges()
        val requestedNumberUp = numberUpValues.getOrNull(numberUpSpinner.selectedItemPosition)
        val options = IppJobOptions(
            copies = selectedCopies(printer),
            pageRanges = if (layoutBakedIntoDocument) emptyList() else requestedPageRanges,
            colorMode = colorMode,
            media = media,
            printScaling = if (layoutBakedIntoDocument) printer.printScalings.firstOrNull { it.equals("none", true) }
                else requestedPrintScaling,
            sides = sidesValues.getOrNull(sidesSpinner.selectedItemPosition),
            orientation = if (layoutBakedIntoDocument) null else requestedOrientation,
            printQuality = printQualityValues.getOrNull(printQualitySpinner.selectedItemPosition),
            resolution = resolutionValues.getOrNull(resolutionSpinner.selectedItemPosition),
            numberUp = if (layoutBakedIntoDocument) null else requestedNumberUp,
            xImagePosition = if (layoutBakedIntoDocument) null else requestedXPosition,
            yImagePosition = if (layoutBakedIntoDocument) null else requestedYPosition,
        )
        val submissionMethod = submissionMethodValues.getOrElse(submissionMethodSpinner.selectedItemPosition) { JobSubmissionMethod.AUTO }
        val contentLayout = selectedContentLayout()
        val layoutPdf = applyLayoutToPdf.isChecked
        io.execute {
            val result = runCatching {
                if (remoteUri != null) {
                    val remoteName = java.net.URI(remoteUri).path.substringAfterLast('/').ifBlank { "원격 문서" }
                    IppClient.printRemoteUri(printer, remoteUri, remoteName, options)
                } else {
                    val prepared = selectedFile?.let { prepareFile(printer, it, contentLayout, layoutPdf) }
                        ?: prepareTypedText(printer, contentLayout)
                    val finalPrepared = if (prepared.mime == "application/pdf") {
                        prepared.copy(bytes = applySheetOptions(prepared.bytes, requestedPageRanges, requestedNumberUp ?: 1, contentLayout))
                    } else prepared
                    val finalOptions = if (finalPrepared.mime == "application/pdf") options else options.copy(
                        pageRanges = requestedPageRanges,
                        // The app has already applied the selected page layout.
                        // Prevent the printer from fitting/scaling it a second time.
                        printScaling = options.printScaling,
                        orientation = null,
                        numberUp = requestedNumberUp,
                        xImagePosition = null,
                        yImagePosition = null,
                    )
                    IppClient.print(printer, finalPrepared.bytes, finalPrepared.mime, finalPrepared.displayName, finalOptions, submissionMethod)
                }
            }.getOrElse { PrintResult(false, null, null, null, it.message ?: "문서를 준비하는 중 알 수 없는 오류가 발생했습니다") }
            main.post {
                if (result.accepted) {
                    updateStatus("${result.method ?: "IPP"} 수락됨 (job-id ${result.jobId ?: "-"}) — 실제 용지 출력은 직접 확인하세요", StatusTone.SUCCESS)
                } else if (result.authRequired) {
                    updateStatus(
                        "출력 전에 프린터 로그인이 필요합니다: ${result.authChallenge?.label ?: "방식 미확인"}",
                        StatusTone.ERROR,
                    )
                    showPrinterAuthDialog(printer, result.authChallenge) {
                        sendOneJob(printer)
                    }
                } else {
                    val error = result.error ?: "IPP 0x${result.ippStatus?.toString(16)}"
                    updateStatus(if (error.startsWith("전송 실패:")) error else "출력 요청 실패: $error", StatusTone.ERROR)
                }
            }
        }
    }

    private fun prepareTypedText(printer: PrinterRecord, contentLayout: ContentLayoutOptions): PreparedDocument {
        val text = textBody.text.toString()
        val formats = printer.formats
        val pdfMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PDF)
        val postScriptMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.POSTSCRIPT)
        val pwgMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PWG_RASTER)
        val urfMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.URF)
        val pclMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PCL)
        val textMime = PrintFormatSupport.advertisedMime(formats, "text/plain")
        return when {
            forcePostScript.isChecked && postScriptMime != null ->
                PreparedDocument(DocumentRenderer.textPostScript("", text, contentLayout), postScriptMime, "휴대폰 텍스트.ps")
            forcePostScript.isChecked ->
                error("선택한 프린터가 PostScript 출력을 광고하지 않습니다")
            pdfMime != null -> PreparedDocument(DocumentRenderer.textPdf("", text, contentLayout), pdfMime, "휴대폰 텍스트.pdf")
            postScriptMime != null -> PreparedDocument(DocumentRenderer.textPostScript("", text, contentLayout), postScriptMime, "휴대폰 텍스트.ps")
            pwgMime != null -> PreparedDocument(DocumentRenderer.textPwgRaster("", text, contentLayout), pwgMime, "휴대폰 텍스트.pwg")
            urfMime != null -> PreparedDocument(DocumentRenderer.textUrf("", text, contentLayout), urfMime, "휴대폰 텍스트.urf")
            pclMime != null -> PreparedDocument(DocumentRenderer.textPcl("", text, contentLayout), pclMime, "휴대폰 텍스트.pcl")
            textMime != null -> PreparedDocument(text.toByteArray(Charsets.UTF_8), textMime, "휴대폰 텍스트.txt")
            else -> error("이 프린터는 앱이 생성할 수 있는 PDF, PostScript, PCL 또는 text/plain을 지원하지 않습니다")
        }
    }

    private fun prepareFile(
        printer: PrinterRecord,
        uri: Uri,
        contentLayout: ContentLayoutOptions,
        layoutPdf: Boolean,
    ): PreparedDocument {
        val name = fileDisplayName(uri)
        val bytes = readFileLimited(uri, MAX_FILE_BYTES)
        val sourceMime = detectMime(uri, name, bytes)
        val canonicalSource = PrintFormatSupport.normalize(sourceMime)
        val formats = printer.formats
        val pdfMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PDF)
        val postScriptMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.POSTSCRIPT)
        val pwgMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PWG_RASTER)
        val urfMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.URF)
        val pclMime = PrintFormatSupport.advertisedMime(formats, PrintFormatSupport.PCL)
        val directMime = PrintFormatSupport.advertisedMime(formats, canonicalSource)
        val prepared = when {
            forcePostScript.isChecked && postScriptMime == null ->
                error("선택한 프린터가 PostScript 출력을 광고하지 않습니다")
            forcePostScript.isChecked && canonicalSource == PrintFormatSupport.PDF -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfPostScript(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, postScriptMime!!, "$name.ps")
            }
            forcePostScript.isChecked && canonicalSource.startsWith("image/") ->
                PreparedDocument(DocumentRenderer.imagePostScript(bytes, contentLayout), postScriptMime!!, "$name.ps")
            forcePostScript.isChecked && canonicalSource == "text/plain" ->
                PreparedDocument(
                    DocumentRenderer.textPostScript(name, bytes.toString(Charsets.UTF_8), contentLayout),
                    postScriptMime!!,
                    "$name.ps",
                )
            forcePostScript.isChecked ->
                error("이 파일은 앱에서 PostScript로 변환할 수 없습니다. PDF, 사진 또는 일반 텍스트 파일을 선택하세요")
            canonicalSource == PrintFormatSupport.PDF && pdfMime != null &&
                (layoutPdf || contentLayout.rotationDegrees != 0f) -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfWithLayout(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, pdfMime, "배치-$name")
            }
            canonicalSource.startsWith("image/") && pdfMime != null ->
                PreparedDocument(DocumentRenderer.imagePdf(bytes, contentLayout), pdfMime, "$name.pdf")
            canonicalSource == "text/plain" && pdfMime != null ->
                PreparedDocument(DocumentRenderer.textPdf(name, bytes.toString(Charsets.UTF_8), contentLayout), pdfMime, "$name.pdf")
            directMime != null -> PreparedDocument(bytes, directMime, name)
            canonicalSource == PrintFormatSupport.PDF && postScriptMime != null -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfPostScript(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, postScriptMime, "$name.ps")
            }
            canonicalSource.startsWith("image/") && postScriptMime != null ->
                PreparedDocument(DocumentRenderer.imagePostScript(bytes, contentLayout), postScriptMime, "$name.ps")
            canonicalSource == "text/plain" && postScriptMime != null ->
                PreparedDocument(DocumentRenderer.textPostScript(name, bytes.toString(Charsets.UTF_8), contentLayout), postScriptMime, "$name.ps")
            canonicalSource == PrintFormatSupport.PDF && pwgMime != null -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfPwgRaster(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, pwgMime, "$name.pwg")
            }
            canonicalSource.startsWith("image/") && pwgMime != null ->
                PreparedDocument(DocumentRenderer.imagePwgRaster(bytes, contentLayout), pwgMime, "$name.pwg")
            canonicalSource == "text/plain" && pwgMime != null ->
                PreparedDocument(DocumentRenderer.textPwgRaster(name, bytes.toString(Charsets.UTF_8), contentLayout), pwgMime, "$name.pwg")
            canonicalSource == PrintFormatSupport.PDF && urfMime != null -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfUrf(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, urfMime, "$name.urf")
            }
            canonicalSource.startsWith("image/") && urfMime != null ->
                PreparedDocument(DocumentRenderer.imageUrf(bytes, contentLayout), urfMime, "$name.urf")
            canonicalSource == "text/plain" && urfMime != null ->
                PreparedDocument(DocumentRenderer.textUrf(name, bytes.toString(Charsets.UTF_8), contentLayout), urfMime, "$name.urf")
            canonicalSource == PrintFormatSupport.PDF && pclMime != null -> {
                val rendered = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    DocumentRenderer.pdfPcl(descriptor, contentLayout)
                } ?: error("PDF 파일을 열 수 없습니다")
                PreparedDocument(rendered, pclMime, "$name.pcl")
            }
            canonicalSource.startsWith("image/") && pclMime != null ->
                PreparedDocument(DocumentRenderer.imagePcl(bytes, contentLayout), pclMime, "$name.pcl")
            canonicalSource == "text/plain" && pclMime != null ->
                PreparedDocument(DocumentRenderer.textPcl(name, bytes.toString(Charsets.UTF_8), contentLayout), pclMime, "$name.pcl")
            else -> error(PrintFormatSupport.unsupportedFileMessage(name, sourceMime, formats))
        }
        require(prepared.bytes.size <= MAX_FILE_BYTES) {
            "변환된 출력 데이터가 50MB를 초과합니다 (${prepared.bytes.size / 1024 / 1024}MB)"
        }
        return prepared
    }

    private fun selectedContentLayout(): ContentLayoutOptions {
        val orientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition)
            ?: selected?.defaultOrientation
        val media = mediaValues.getOrNull(mediaSpinner.selectedItemPosition) ?: selected?.defaultMedia
        val (pageWidth, pageHeight) = mediaPageSize(media)
        val ippX = xPositionValues.getOrNull(xPositionSpinner.selectedItemPosition)?.lowercase()
        val ippY = yPositionValues.getOrNull(yPositionSpinner.selectedItemPosition)?.lowercase()
        val scalePercent = contentScalePercentInput.text.toString().trim().toFloatOrNull() ?: 100f
        val offsetXmm = contentOffsetXInput.text.toString().trim().toFloatOrNull() ?: 0f
        val offsetYmm = contentOffsetYInput.text.toString().trim().toFloatOrNull() ?: 0f
        val printableMarginsMm = advertisedPrinterMargins(selected) ?: listOf(5f, 5f, 5f, 5f)
        val printableMarginsPt = printableMarginsMm.map { (it * 72f / 25.4f).toInt().coerceAtLeast(0) }
        require(scalePercent in 10f..400f) { "내용 크기는 10~400%로 입력하세요" }
        require(offsetXmm in -300f..300f && offsetYmm in -300f..300f) { "이동값은 -300~300mm로 입력하세요" }
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
            scale = when (contentScaleSpinner.selectedItemPosition) {
                1 -> ContentScale.FILL
                2 -> ContentScale.FULL_BLEED_FILL
                3 -> ContentScale.PRINTABLE_FIT
                4 -> ContentScale.ACTUAL
                else -> ContentScale.FIT
            },
            margin = contentMarginValues.getOrElse(contentMarginSpinner.selectedItemPosition) { 43 },
            textSize = listOf(12f, 15f, 20f).getOrElse(contentTextSizeSpinner.selectedItemPosition) { 15f },
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

    private fun mediaPageSize(media: String?): Pair<Int, Int> {
        val value = media?.lowercase().orEmpty()
        val dimensions = Regex(".*_(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)(mm|in)$").matchEntire(value)
        if (dimensions != null) {
            val width = dimensions.groupValues[1].toFloat()
            val height = dimensions.groupValues[2].toFloat()
            val pointsPerUnit = if (dimensions.groupValues[3] == "in") 72f else 72f / 25.4f
            return (width * pointsPerUnit).toInt().coerceAtLeast(1) to
                (height * pointsPerUnit).toInt().coerceAtLeast(1)
        }
        return when (value) {
            "iso_a3_297x420mm" -> 842 to 1_191
            "iso_a5_148x210mm" -> 420 to 595
            "na_letter_8.5x11in" -> 612 to 792
            "na_legal_8.5x14in" -> 612 to 1_008
            else -> 595 to 842
        }
    }

    private fun selectedPaperLabel(): String {
        val media = mediaValues.getOrNull(mediaSpinner.selectedItemPosition) ?: selected?.defaultMedia
        val base = media?.let(::mediaLabel) ?: "A4 (210×297mm)"
        val orientation = orientationValues.getOrNull(orientationSpinner.selectedItemPosition) ?: selected?.defaultOrientation
        return when (orientation) {
            4 -> "$base · 용지 고정 · 내용 90° 회전"
            5 -> "$base · 용지 고정 · 내용 270° 회전"
            6 -> "$base · 용지 고정 · 내용 180° 회전"
            else -> "$base · 용지 고정 · 내용 세로"
        }
    }

    private fun selectedCopies(printer: PrinterRecord): Int {
        val value = copiesInput.text.toString().trim().toIntOrNull() ?: 1
        val supported = printer.copiesSupported
        require(value >= 1) { "복사 매수는 1 이상이어야 합니다" }
        if (supported != null) require(value in supported.lower..supported.upper) {
            "이 프린터의 복사 매수 범위는 ${supported.lower}~${supported.upper}부입니다"
        }
        return value
    }

    private fun selectedPageRanges(): List<IppRange> {
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

    private fun readFileLimited(uri: Uri, limit: Int): ByteArray {
        return contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "파일은 최대 ${limit / 1024 / 1024}MB까지 전송할 수 있습니다" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("파일을 읽을 수 없습니다")
    }

    private fun fileDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "휴대폰 파일"
        }
        return uri.lastPathSegment ?: "휴대폰 파일"
    }

    private fun detectMime(uri: Uri, name: String, bytes: ByteArray): String {
        contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }?.let { return it.lowercase() }
        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) return "application/pdf"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
            "tif", "tiff" -> "image/tiff"; "txt", "csv", "log" -> "text/plain"
            "webp" -> "image/webp"; "bmp" -> "image/bmp"; "heic" -> "image/heic"; "heif" -> "image/heif"
            "ps" -> "application/postscript"; "pcl" -> "application/vnd.hp-pcl"
            "pclxl" -> "application/vnd.hp-pclxl"; "pwg", "ras" -> "image/pwg-raster"
            "urf" -> "image/urf"; "pclm" -> "application/pclm"
            else -> "application/octet-stream"
        }
    }

    private fun requestNearbyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 51)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        lastBgpScope?.let { scope ->
            outState.putString(STATE_BGP_EGRESS, scope.egressIp)
            outState.putString(STATE_BGP_PREFIX, scope.prefix)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mdnsFinishRunnable?.let(main::removeCallbacks)
        mdnsCountdownRunnable?.let(main::removeCallbacks)
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        discovery.close()
        io.shutdownNow()
        if (isFinishing) IppClient.clearAllCredentials()
        super.onDestroy()
    }

    companion object {
        private const val MAX_FILE_BYTES = 50 * 1024 * 1024
        private const val MAX_SAVED_PRINTERS = 20
        private const val PREFS_NAME = "ipp_printer_connect"
        private const val PREF_SAVED_PRINTERS = "saved_printers_json"
        private const val PREF_LAST_PRINTED_PRINTER = "last_printed_printer_uri"
        private const val STATE_BGP_EGRESS = "bgp-egress"
        private const val STATE_BGP_PREFIX = "bgp-prefix"
    }
}
