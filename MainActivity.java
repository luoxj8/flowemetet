package com.luo.stopwatch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {

    // ==================== 常量定义 ====================
    private static final double[] DEFAULT_LITERS = {0.5, 1, 1.5, 1.6, 2, 2.5, 3, 3.5, 4, 6, 8};
    private static final String[][] UNITS = {
        {"升/分钟", "L/min", "60"}, {"Gal/min", "Gal/min", "15.8503"},
        {"m³/小时", "m³/小时", "3.6"}, {"升/小时", "L/h", "3600"}
    };
    private static final String[] CB_LABELS = {"提示音", "防误触", "顶部手电", "隔行变色"};
    private static final String[] RGB_LABELS = {"红", "绿", "蓝"};
    private static final int[] PRESET_BG = {Color.BLACK, Color.WHITE, 0xFF600050, 0xFF206050,
        0xFF203060, 0xFF303025, 0xFF404055};
    private static final int[] PRESET_TXT = {Integer.MIN_VALUE, 0xFFFF0000, 0xFF50FF50,
        0xFF0000AA, 0xFFFFFF60, 0xFFA000FF, 0xFFFF00A0};
    private static final int CARD_BG = 0xFF656565, DIALOG_BG = 0xFF505050,
        TXT_SEC = 0xFFCCCCCC, ACCENT = 0xFF66CCFF;

    // 结果列与单位列的权重比例（结果 : 单位 = 3 : 1）
    private static final float RESULT_WEIGHT = 3f;
    private static final float UNIT_WEIGHT = 1f;

    // 列表左右内边距占屏幕宽度的比例（左右各占 4%）
    private static final float SIDE_MARGIN_RATIO = 0.04f;

    // 字体缩放基准宽度（dp），以此宽度为 1.0 倍进行缩放
    private static final float BASE_WIDTH_DP = 360f;
    // 字体缩放系数上下限
    private static final float FONT_SCALE_MIN = 0.80f;
    private static final float FONT_SCALE_MAX = 1.60f;

    // ==================== 字体缓存 ====================
    public static class FontCache {
        private static Typeface sMono;

        public static Typeface getMono(Context context) {
            if (sMono == null) {
                try {
                    sMono = Typeface.createFromAsset(context.getAssets(), "jetbrainsmono_regular.ttf");
                } catch (Exception e) {
                    sMono = Typeface.MONOSPACE;
                }
            }
            return sMono;
        }
    }

    // ==================== 状态变量 ====================
    private TextView tvTime, hint, menuTv;
    private LinearLayout resultLay;
    private TextView[] tvResults, tvUnits;
    private FrameLayout root;
    private View autoSwatch;
    private TextView autoSwatchText;
    private SeekBar[] txtSliders;
    private boolean ignoreTxtSliders;
    private boolean uiBuilt;
    private ToneGenerator toneGenerator;

    private boolean running, flashOn, enableRow = true, antiTouch, vol;
    private long startMs, nextMs;
    private double[] liters = DEFAULT_LITERS.clone();
    private String unit = UNITS[0][1];
    private int bg = Color.BLACK, txt = Integer.MIN_VALUE, txtSize = 32, refresh = 33, margin;
    private final int[] rgb = new int[3];
    private double vt = 60;

    // 屏幕宽度比例系数（基准 360dp）
    private float fontScale = 1f;

    private int autoTxtCache, autoRowCache;
    private boolean txtCacheValid, rowCacheValid;
    private String lastUnit = "";
    private final StringBuilder fmtBuilder = new StringBuilder(256);
    private final Formatter formatter = new Formatter(fmtBuilder, Locale.ROOT);
    private SharedPreferences prefs;

    private Typeface tf;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::timerTick;
    private CameraManager camMan;
    private String camId;
    private Dialog settingsDialog;

    private ViewGroup miniPreviewLayout;
    private TextView miniTime;
    private final List<TextView> miniResults = new ArrayList<>();
    private final List<TextView> miniUnits = new ArrayList<>();
    private final List<View> miniRows = new ArrayList<>();

    // ==================== 生命周期方法 ====================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        initializeBasicComponents();
        setupWindow();
        loadSavedState();
        buildBaseUI();
        applyConfiguration();
        getWindow().getDecorView().post(this::initializeDelayedComponents);
    }

    private void initializeBasicComponents() {
        if (Build.VERSION.SDK_INT >= 31) {
            getSplashScreen().setOnExitAnimationListener(splashScreenView -> splashScreenView.remove());
        }
        setContentView(R.layout.activity_main);
        tf = FontCache.getMono(this);
        prefs = getSharedPreferences("flow", MODE_PRIVATE);
    }

    private void setupWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void initializeDelayedComponents() {
        initSound();
        initCamera();
        buildResultRows();
    }

    // ==================== UI构建方法 ====================
    private void buildBaseUI() {
        // 先计算屏幕宽度比例，之后所有字号都基于此缩放
        fontScale = computeFontScale();

        root = findViewById(R.id.rootLayout);
        hint = findViewById(R.id.hintTextView);
        tvTime = findViewById(R.id.timerTextView);
        resultLay = findViewById(R.id.resultLayout);
        menuTv = findViewById(R.id.menuTextView);

        setupClickListeners();
        configureTimerTextView();
    }

    /** 依据屏幕宽度（dp）计算字体缩放系数，带上下限保护 */
    private float computeFontScale() {
        float widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) widthDp = BASE_WIDTH_DP;
        float scale = widthDp / BASE_WIDTH_DP;
        if (scale < FONT_SCALE_MIN) scale = FONT_SCALE_MIN;
        if (scale > FONT_SCALE_MAX) scale = FONT_SCALE_MAX;
        return scale;
    }

    private void setupClickListeners() {
        View.OnClickListener toSettings = v -> openSettings();
        tvTime.setOnClickListener(v -> toggleFlashlight());
        menuTv.setOnClickListener(toSettings);
        hint.setOnClickListener(toSettings);
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !antiTouch) {
                toggleTimer();
                return true;
            }
            return false;
        });
    }

    private void configureTimerTextView() {
        tvTime.setTypeface(tf, Typeface.BOLD);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, (txtSize + 3) * fontScale);
        tvTime.setPaintFlags(tvTime.getPaintFlags() | Paint.SUBPIXEL_TEXT_FLAG);
    }

    private void buildResultRows() {
        resultLay.removeAllViews();
        int len = liters.length;
        tvResults = new TextView[len];
        tvUnits = new TextView[len];

        int txtColor = getAutoTextColor();
        int rowColor = getAutoRowColor();
        int halfMarginPx = dp(margin) / 2;

        // 左右内边距按屏幕宽度比例计算
        int sidePaddingPx = (int)(getResources().getDisplayMetrics().widthPixels * SIDE_MARGIN_RATIO);

        for (int i = 0; i < len; i++) {
            createResultRow(i, txtColor, rowColor, halfMarginPx, sidePaddingPx);
        }

        uiBuilt = true;
        lastUnit = "";
        if (!running) {
            updateTimerDisplay(0);
        } else {
            updateTimerDisplay((SystemClock.elapsedRealtime() - startMs) / 1000.0);
        }
    }

    /**
     * 创建结果行：
     *  - 左右 padding 按屏幕宽度比例。
     *  - 结果列 : 单位列 = 3 : 1 权重分配剩余宽度。
     *  - 字号按 fontScale 缩放，跨设备视觉比例一致。
     */
    private void createResultRow(int index, int txtColor, int rowColor,
                                 int halfMarginPx, int sidePaddingPx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(sidePaddingPx, halfMarginPx, sidePaddingPx, halfMarginPx);

        if (enableRow && (index & 1) == 0) {
            row.setBackgroundColor(rowColor);
        }

        float resultSizeSp = txtSize * fontScale;
        float unitSizeSp = (txtSize ) * fontScale;

        tvResults[index] = createProportionalTextView(txtColor, RESULT_WEIGHT, resultSizeSp);
        tvResults[index].setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        tvUnits[index] = createProportionalTextView(txtColor, UNIT_WEIGHT, unitSizeSp);
        tvUnits[index].setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        row.addView(tvResults[index]);
        row.addView(tvUnits[index]);
        resultLay.addView(row);
    }

    /** 按权重比例创建 TextView，字号按 sp */
    private TextView createProportionalTextView(int color, float weight, float sizeSp) {
        TextView textView = new TextView(this);
        textView.setTextColor(color);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        textView.setSingleLine();
        textView.setTypeface(tf);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.gravity = Gravity.CENTER_VERTICAL;
        textView.setLayoutParams(params);

        return textView;
    }

    // ==================== 配置应用方法 ====================
    private void applyConfiguration() {
        int textColor = getAutoTextColor();
        root.setBackgroundColor(bg);

        tvTime.setTextColor(getTimeColor());
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, (txtSize + 3) * fontScale);

        hint.setTextColor(textColor);
        menuTv.setTextColor(textColor);

        if (tvResults != null) {
            for (int i = 0; i < tvResults.length; i++) {
                if (tvResults[i] != null) {
                    tvResults[i].setTextColor(textColor);
                    tvResults[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, txtSize * fontScale);
                }
                if (tvUnits[i] != null) {
                    tvUnits[i].setTextColor(textColor);
                    tvUnits[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, (txtSize ) * fontScale);
                }
            }
        }

        setupSystemBars();
    }

    private void setupSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);

        if (Build.VERSION.SDK_INT >= 23) {
            int flags = window.getDecorView().getSystemUiVisibility();
            boolean isLightBackground = calculateLuminance(bg) > 0.5f;

            if (isLightBackground) {
                window.getDecorView().setSystemUiVisibility(flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                window.getDecorView().setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    // ==================== 计时控制方法 ====================
    private void toggleTimer() {
        if (running) {
            stopTimer();
        } else {
            startTimer();
        }
        playClickSound();
    }

    private void startTimer() {
        running = true;
        startMs = SystemClock.elapsedRealtime();
        nextMs = startMs + refresh;
        updateTimerDisplay(0);
        handler.post(ticker);
    }

    private void timerTick() {
        if (!running) return;

        long now = SystemClock.elapsedRealtime();
        if (now < nextMs) {
            handler.postDelayed(ticker, nextMs - now);
            return;
        }

        updateTimerDisplay((now - startMs) / 1000.0);
        nextMs = Math.max(nextMs + refresh, now + 1);
        handler.post(ticker);
    }

    private void stopTimer() {
        running = false;
        handler.removeCallbacks(ticker);
        updateTimerDisplay((SystemClock.elapsedRealtime() - startMs) / 1000.0);
    }

    private void updateTimerDisplay(double seconds) {
    if (!uiBuilt) return;

    fmtBuilder.setLength(0);
    formatter.format("%.3f", seconds);
    formatter.flush();
    tvTime.setText(fmtBuilder.toString());

    boolean unitChanged = !unit.equals(lastUnit);
    if (unitChanged) {
        lastUnit = unit;
    }

    double factor = seconds > 0 ? vt / seconds : 0;
    for (int i = 0; i < liters.length; i++) {
        // 先算出原始流量，再向下截断到 4 位小数（不四舍五入）
        double flow = liters[i] * factor;
        double truncated = (int)(flow * 10000.0) / 10000.0;

        fmtBuilder.setLength(0);
        formatter.format("%.1fL    %.4f", liters[i], truncated);
        formatter.flush();
        tvResults[i].setText(fmtBuilder.toString());

        if (unitChanged) {
            tvUnits[i].setText(unit);
        }
    }
}

    // ==================== 手电筒控制方法 ====================
    private void toggleFlashlight() {
        if (camMan != null && camId != null) {
            try {
                flashOn = !flashOn;
                camMan.setTorchMode(camId, flashOn);
            } catch (Exception e) {
                flashOn = false;
            }
        }
    }

    private void initCamera() {
        if (camMan != null) return;

        try {
            camMan = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : camMan.getCameraIdList()) {
                if (camMan.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_BACK) {
                    camId = id;
                    return;
                }
            }
        } catch (Exception e) {
            // 相机不可用，忽略错误
        }
    }

    // ==================== 音效控制方法 ====================
    private void initSound() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (Exception e) {
            toneGenerator = null;
        }
    }

    private void playClickSound() {
        if (vol && toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
        }
    }

    // ==================== 颜色计算方法 ====================
    private float calculateLuminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f;
    }

    private int invertColor(int color) {
        int r = 255 - Color.red(color);
        int g = 255 - Color.green(color);
        int b = 255 - Color.blue(color);

        if (Math.abs(r - Color.red(color)) < 120 &&
            Math.abs(g - Color.green(color)) < 120 &&
            Math.abs(b - Color.blue(color)) < 120) {
            return Color.GREEN;
        }
        return Color.rgb(r, g, b);
    }

    private int getAutoTextColor() {
        if (!txtCacheValid) {
            autoTxtCache = txt != Integer.MIN_VALUE
                ? txt
                : (calculateLuminance(bg) > 0.5f ? Color.BLACK : Color.WHITE);
            txtCacheValid = true;
        }
        return autoTxtCache;
    }

    private int getAutoRowColor() {
        if (!rowCacheValid) {
            int r = Color.red(bg);
            int g = Color.green(bg);
            int b = Color.blue(bg);

            if (r < 30 && g < 30 && b < 30) {
                autoRowCache = 0xFF202020;
            } else if (r >= 230 && g >= 230 && b >= 230) {
                autoRowCache = 0xFFDDDDDD;
            } else {
                autoRowCache = Color.rgb((int)(r * 0.85), (int)(g * 0.85), (int)(b * 0.85));
            }
            rowCacheValid = true;
        }
        return autoRowCache;
    }

    private int[] getCurrentTextColorRgb() {
        int color = txt == Integer.MIN_VALUE ? getAutoTextColor() : txt;
        return new int[]{Color.red(color), Color.green(color), Color.blue(color)};
    }

    private void invalidateColorCache() {
        txtCacheValid = false;
        rowCacheValid = false;
    }

    private int getTimeColor() {
        int r = Color.red(bg);
        int g = Color.green(bg);
        int b = Color.blue(bg);

        if (r < 50 && g < 50 && b < 50) {
            return Color.GREEN;
        } else if (r > 180 && g > 180 && b > 180) {
            return Color.RED;
        } else {
            return invertColor(bg);
        }
    }

    // ==================== 设置面板方法（保持原有像素样式） ====================
    private void openSettings() {
        Dialog dialog = new Dialog(this, R.style.AppTheme);
        settingsDialog = dialog;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout mainLayout = createSettingsLayout();

        LinearLayout flowCard = createCard(mainLayout, "数字间用空格分隔,长按取消重置");
        EditText litersInput = createLitersInput(flowCard);
        createUnitSelector(flowCard);
        CheckBox[] checkBoxes = createFunctionCheckboxes(flowCard);
        createSlider(flowCard, "字体", txtSize - 20, 30, 20, "sp", v -> txtSize = v + 20);
        createSlider(flowCard, "行距", margin, 50, 0, "px", v -> margin = v);
        createSlider(flowCard, "刷新", refresh - 33, 967, 33, "ms", v -> refresh = v + 33);

        LinearLayout colorCard = createCard(mainLayout, "");
        setupColorPicker(colorCard);

        mainLayout.addView(createButtonRow(dialog, litersInput, checkBoxes));
        mainLayout.addView(createVersionInfo());

        scrollView.addView(mainLayout);
        dialog.setContentView(scrollView);
        setupDialogWindow(dialog);
        dialog.show();
    }

    private LinearLayout createSettingsLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(25, 5, 25, 5);
        layout.setBackgroundColor(DIALOG_BG);
        return layout;
    }

    private EditText createLitersInput(LinearLayout parent) {
        EditText input = new EditText(this);
        input.setText(convertLitersToString(liters));
        input.setHint("例如：0.5 1 1.5 2");
        input.setTextColor(0xFF333333);
        input.setHintTextColor(0xFF999999);
        input.setBackground(createRoundedDrawable(0xFFEEEEDD, 12));
        input.setPadding(5, 10, 5, 10);
        input.setTextSize(16);
        input.setMaxLines(1);
        parent.addView(input);
        return input;
    }

    private void createUnitSelector(LinearLayout parent) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);

        int selectedIndex = 0;
        for (int i = 0; i < UNITS.length; i++) {
            if (unit.equals(UNITS[i][1]) && Math.abs(vt - Double.parseDouble(UNITS[i][2])) < 0.0001) {
                selectedIndex = i;
                break;
            }
        }

        for (int i = 0; i < UNITS.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(1000 + i);
            button.setText(UNITS[i][0]);
            button.setTextColor(TXT_SEC);
            button.setTextSize(11);
            button.setPadding(5, 10, 5, 10);
            button.setLayoutParams(new RadioGroup.LayoutParams(0, -2, 1f));
            button.setChecked(i == selectedIndex);
            group.addView(button);
        }

        group.setOnCheckedChangeListener((radioGroup, id) -> {
            int index = id - 1000;
            unit = UNITS[index][1];
            vt = Double.parseDouble(UNITS[index][2]);
        });

        parent.addView(group);
    }

    private CheckBox[] createFunctionCheckboxes(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 10, 0, 0);

        CheckBox[] checkBoxes = new CheckBox[4];
        boolean[] values = {vol, antiTouch, flashOn, enableRow};

        for (int i = 0; i < 4; i++) {
            checkBoxes[i] = new CheckBox(this);
            checkBoxes[i].setText(CB_LABELS[i]);
            checkBoxes[i].setTextColor(TXT_SEC);
            checkBoxes[i].setTextSize(10);
            checkBoxes[i].setChecked(values[i]);
            checkBoxes[i].setPadding(5, 10, 5, 10);
            row.addView(checkBoxes[i], new LinearLayout.LayoutParams(0, -2, 1f));
        }

        parent.addView(row);
        return checkBoxes;
    }

    private SeekBar createSlider(LinearLayout parent, String label, int progress, int max,
                                 int base, String unitStr, IntConsumer callback) {
        return createSlider(parent, label, progress, max, base, unitStr, callback, null);
    }

    private SeekBar createSlider(LinearLayout parent, String label, int progress, int max,
                                 int base, String unitStr, IntConsumer callback, Integer textColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, textColor != null ? 25 : 15, 0, textColor != null ? 20 : 35);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(textColor != null ? textColor : TXT_SEC);
        labelView.setTextSize(textColor != null ? 16 : 14);
        if (textColor != null) {
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
        }
        labelView.setPadding(0, 0, 15, 0);
        row.addView(labelView);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max);
        seekBar.setProgress(progress);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(seekBar);

        TextView valueView = null;
        if (unitStr != null) {
            valueView = new TextView(this);
            valueView.setText((progress + base) + unitStr);
            valueView.setTextColor(ACCENT);
            valueView.setTextSize(14);
            valueView.setTypeface(Typeface.DEFAULT_BOLD);
            valueView.setPadding(15, 0, 0, 0);
            row.addView(valueView);
        }

        final TextView finalValueView = valueView;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean f) {
                if (finalValueView != null) {
                    finalValueView.setText((p + base) + unitStr);
                }
                if (callback != null) {
                    callback.accept(p);
                }
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        parent.addView(row);
        return seekBar;
    }

    private LinearLayout createButtonRow(Dialog dialog, EditText input, CheckBox[] checkBoxes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 20, 0, 20);

        Button cancelButton = createButton("❌ 取消", 0xFF757575, v -> {
            loadSavedState();
            applyConfiguration();
            dialog.dismiss();
        });

        cancelButton.setOnLongClickListener(v -> {
            resetToDefaults();
            saveState();
            invalidateColorCache();
            buildResultRows();
            applyConfiguration();
            dialog.dismiss();
            return true;
        });

        Button saveButton = createButton("✅ 保存", 0xFF4CAF50, v -> saveAndExit(dialog, input, checkBoxes));

        row.addView(cancelButton, createLayoutParams(0, -2, 1f, 0, 5, 20, 0));
        row.addView(saveButton, createLayoutParams(0, -2, 1f, 20, 5, 0, 0));

        return row;
    }

    private void saveAndExit(Dialog dialog, EditText input, CheckBox[] checkBoxes) {
        String text = input.getText().toString().trim().replaceAll("[^0-9.\\s]+", " ");
        List<Double> values = new ArrayList<>();

        if (!text.isEmpty()) {
            for (String part : text.split("\\s+")) {
                try {
                    double v = Double.parseDouble(part);
                    if (Double.isFinite(v) && v > 0) {
                        values.add(v);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (values.isEmpty()) {
            liters = DEFAULT_LITERS.clone();
        } else {
            int count = Math.min(values.size(), 50);
            liters = new double[count];
            for (int i = 0; i < count; i++) {
                liters[i] = values.get(i);
            }
        }

        vol = checkBoxes[0].isChecked();
        antiTouch = checkBoxes[1].isChecked();
        flashOn = checkBoxes[2].isChecked();
        enableRow = checkBoxes[3].isChecked();

        if (camMan != null && camId != null) {
            try {
                camMan.setTorchMode(camId, flashOn);
            } catch (Exception ignored) {
            }
        }

        saveState();
        invalidateColorCache();
        buildResultRows();
        applyConfiguration();
        dialog.dismiss();
    }

    private TextView createVersionInfo() {
        TextView textView = new TextView(this);
        String url = "https://wwbv.lanzout.com/b014wqs1qf";
        String qq = "1090669724";
        String text = "发布地址：" + url + "\n分享码：0000\n版本：16.2 \n鑫泰科技luoxiaojun开发\n反馈：" + qq;

        SpannableString spannableString = new SpannableString(text);
        int urlStart = text.indexOf(url);
        int qqStart = text.indexOf(qq);

        spannableString.setSpan(new URLSpan(url), urlStart, urlStart + url.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannableString.setSpan(new ClickableSpan() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("mqq://card/show_pslcard?src_type=internal&uin=" + qq + "&card_type=group")));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/" + qq)));
                    } catch (Exception e2) {
                        Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            public void updateDrawState(TextPaint ds) {
                ds.setColor(0xFF66CCFF);
                ds.setUnderlineText(true);
            }
        }, qqStart, qqStart + qq.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        textView.setText(spannableString);
        textView.setTextColor(0xFF666666);
        textView.setTextSize(10);
        textView.setGravity(Gravity.CENTER);
        textView.setLineSpacing(10, 0.9f);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setPadding(0, 90, 0, 35);

        return textView;
    }

    private Button createButton(String text, int bgColor, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setBackground(createRoundedDrawable(bgColor, 15));
        button.setPadding(40, 25, 40, 25);
        button.setOnClickListener(listener);
        return button;
    }

    private void setupDialogWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;

        window.setStatusBarColor(DIALOG_BG);
        window.setNavigationBarColor(DIALOG_BG);

        if (Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ==================== 颜色选择器方法（保持原有像素样式） ====================
    private void setupColorPicker(LinearLayout parent) {
        buildMiniPreview(parent);

        rgb[0] = Color.red(bg);
        rgb[1] = Color.green(bg);
        rgb[2] = Color.blue(bg);

        int[] colors = {0xFFFF4444, 0xFF44FF44, 0xFF4444FF};
        SeekBar[] sliders = new SeekBar[3];

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, 10, 0, 10);

        for (int preset : PRESET_BG) {
            View swatch = new View(this);
            swatch.setLayoutParams(createLayoutParams(0, dp(40), 1f, 10, 0, 10, 0));
            swatch.setBackground(createRoundedDrawable(preset, dp(8)));
            swatch.setOnClickListener(v -> {
                bg = preset;
                invalidateColorCache();
                rgb[0] = Color.red(preset);
                sliders[0].setProgress(rgb[0]);
                rgb[1] = Color.green(preset);
                sliders[1].setProgress(rgb[1]);
                rgb[2] = Color.blue(preset);
                sliders[2].setProgress(rgb[2]);
                updateMiniPreview();
            });
            presetRow.addView(swatch);
        }
        parent.addView(presetRow);

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            sliders[i] = createSlider(parent, RGB_LABELS[i], rgb[i], 255, 0, null, p -> {
                rgb[idx] = p;
                bg = Color.rgb(rgb[0], rgb[1], rgb[2]);
                invalidateColorCache();
                updateMiniPreview();
            }, colors[i]);
        }

        LinearLayout txtPresetRow = new LinearLayout(this);
        txtPresetRow.setOrientation(LinearLayout.HORIZONTAL);
        txtPresetRow.setPadding(0, 10, 0, 10);

        for (int i = 0; i < PRESET_TXT.length; i++) {
            final int color = PRESET_TXT[i];
            FrameLayout frameLayout = createColorSwatch(i == 0 ? getAutoTextColor() : color, i == 0);

            if (i == 0) {
                autoSwatch = frameLayout.getChildAt(0);
                autoSwatchText = (TextView) frameLayout.getChildAt(1);
            }

            frameLayout.setOnClickListener(v -> {
                txt = color;
                invalidateColorCache();
                applyConfiguration();
                updateMiniPreview();
                syncTextSlidersToCurrent();
            });
            txtPresetRow.addView(frameLayout);
        }
        parent.addView(txtPresetRow);

        int[] txtRgb = getCurrentTextColorRgb();
        int[] txtColors = {0xFFFF6666, 0xFF66FF66, 0xFF6666FF};
        txtSliders = new SeekBar[3];

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            txtSliders[i] = createSlider(parent, RGB_LABELS[i], txtRgb[i], 255, 0, null, p -> {
                if (ignoreTxtSliders) return;
                int r = txtSliders[0].getProgress();
                int g = txtSliders[1].getProgress();
                int b = txtSliders[2].getProgress();
                txt = Color.rgb(r, g, b);
                invalidateColorCache();
                applyConfiguration();
                updateMiniPreview();
            }, txtColors[i]);
        }

        miniPreviewLayout.setOnClickListener(v -> {
            Random random = new Random();
            for (int i = 0; i < 3; i++) {
                rgb[i] = random.nextInt(150);
                sliders[i].setProgress(rgb[i]);
            }
            bg = Color.rgb(rgb[0], rgb[1], rgb[2]);
            invalidateColorCache();
            updateMiniPreview();
        });
    }

    private void buildMiniPreview(LinearLayout parent) {
        miniResults.clear();
        miniUnits.clear();
        miniRows.clear();

        // 预览区也用同样的缩放，但限制在合理范围，保证在设置面板里不会过大/过小
        float previewScale = Math.max(0.85f, Math.min(1.30f, fontScale));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(createRoundedDrawable(bg, dp(8)));
        container.setPadding(0, dp(8), 0, dp(8));
        container.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)container.getLayoutParams()).setMargins(0, 0, 0, dp(10));

        miniTime = new TextView(this);
        miniTime.setText("    0.000");
        miniTime.setTextColor(getTimeColor());
        miniTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * previewScale);
        miniTime.setTypeface(tf, Typeface.BOLD);
        miniTime.setGravity(Gravity.LEFT);
        miniTime.setPadding(0, 0, 0, dp(4));
        container.addView(miniTime, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int demoRows = Math.min(3, liters.length);
        for (int i = 0; i < demoRows; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(20), dp(1), dp(20), dp(1));
            if (enableRow && (i & 1) == 0) {
                row.setBackgroundColor(getAutoRowColor());
            }

            TextView result = new TextView(this);
            fmtBuilder.setLength(0);
            formatter.format("%.1fL         0.000", liters[i]);
            formatter.flush();
            result.setText(fmtBuilder.toString());
            result.setTextColor(getAutoTextColor());
            result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * previewScale);
            result.setTypeface(tf);
            result.setSingleLine();
            row.addView(result, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView unitView = new TextView(this);
            unitView.setText(unit);
            unitView.setTextColor(getAutoTextColor());
            unitView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * previewScale);
            unitView.setTypeface(tf);
            unitView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(unitView);

            miniResults.add(result);
            miniUnits.add(unitView);
            miniRows.add(row);
            container.addView(row);
        }

        miniPreviewLayout = container;
        parent.addView(container, 0);
    }

    private void updateMiniPreview() {
        if (miniPreviewLayout == null) return;

        miniPreviewLayout.setBackground(createRoundedDrawable(bg, dp(8)));
        int textColor = getAutoTextColor();
        miniTime.setTextColor(getTimeColor());

        for (TextView tv : miniResults) {
            tv.setTextColor(textColor);
        }
        for (TextView tv : miniUnits) {
            tv.setTextColor(textColor);
        }

        int rowColor = getAutoRowColor();
        for (int i = 0; i < miniRows.size(); i++) {
            View row = miniRows.get(i);
            if (enableRow && (i & 1) == 0) {
                row.setBackgroundColor(rowColor);
            } else {
                row.setBackgroundColor(Color.TRANSPARENT);
            }
        }

        updateAutoSwatch();

        if (txtSliders != null && txt == Integer.MIN_VALUE) {
            int[] autoRgb = getCurrentTextColorRgb();
            ignoreTxtSliders = true;
            for (int i = 0; i < 3; i++) {
                txtSliders[i].setProgress(autoRgb[i]);
            }
            ignoreTxtSliders = false;
        }
    }

    private void syncTextSlidersToCurrent() {
        if (txtSliders == null) return;

        int[] newRgb = getCurrentTextColorRgb();
        ignoreTxtSliders = true;
        for (int i = 0; i < 3; i++) {
            txtSliders[i].setProgress(newRgb[i]);
        }
        ignoreTxtSliders = false;
    }

    private FrameLayout createColorSwatch(int color, boolean auto) {
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        ((LinearLayout.LayoutParams)frameLayout.getLayoutParams()).setMargins(8, 0, 8, 0);

        View view = new View(this);
        view.setBackground(createRoundedDrawable(color, dp(8)));
        frameLayout.addView(view, new FrameLayout.LayoutParams(-1, -1));

        if (!auto) return frameLayout;

        TextView textView = new TextView(this);
        textView.setText("自动");
        textView.setTextColor(calculateLuminance(color) > 0.5f ? Color.BLACK : Color.WHITE);
        textView.setTextSize(11);
        textView.setGravity(Gravity.CENTER);
        frameLayout.addView(textView, new FrameLayout.LayoutParams(-1, -1));

        return frameLayout;
    }

    private void updateAutoSwatch() {
        if (autoSwatch == null) return;

        boolean isLight = calculateLuminance(bg) > 0.5f;
        int color = isLight ? Color.BLACK : Color.WHITE;
        autoSwatch.setBackground(createRoundedDrawable(color, dp(8)));

        if (autoSwatchText != null) {
            autoSwatchText.setTextColor(isLight ? Color.WHITE : Color.BLACK);
        }
    }

    // ==================== 通用辅助方法 ====================
    private LinearLayout createCard(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(30, title == null || title.isEmpty() ? 20 : 25, 30, 25);
        card.setBackground(createRoundedDrawable(CARD_BG, 20));
        card.setLayoutParams(createLayoutParams(-1, -2));
        ((LinearLayout.LayoutParams)card.getLayoutParams()).setMargins(0, 0, 0, 25);

        if (title != null && !title.isEmpty()) {
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextColor(ACCENT);
            titleView.setTextSize(12);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(titleView);
        }

        parent.addView(card);
        return card;
    }

    private GradientDrawable createRoundedDrawable(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams createLayoutParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams createLayoutParams(int width, int height, float weight,
            int marginLeft, int marginTop, int marginRight, int marginBottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(marginLeft, marginTop, marginRight, marginBottom);
        return params;
    }

    private int dp(int value) {
        return (int)(getResources().getDisplayMetrics().density * value + 0.5f);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String convertLitersToString(double[] array) {
        StringBuilder sb = new StringBuilder();
        for (double value : array) {
            sb.append(value).append(' ');
        }
        return sb.toString().trim();
    }

    // ==================== 状态保存与加载 ====================
    private void loadSavedState() {
        String litersStr = prefs.getString("liters", "");
        if (!litersStr.isEmpty()) {
            parseLiters(litersStr.trim().split("\\s+"));
        }

        unit = prefs.getString("unit", UNITS[0][1]);
        txtSize = clamp(prefs.getInt("textSize", 32), 20, 50);
        margin = clamp(prefs.getInt("itemMargin", 5), 0, 100);
        bg = prefs.getInt("bgColor", Color.BLACK);
        txt = prefs.getInt("txtColor", Integer.MIN_VALUE);
        enableRow = prefs.getBoolean("enableRow", true);
        refresh = clamp(prefs.getInt("refreshMs", 33), 33, 1000);
        antiTouch = prefs.getBoolean("antiTouchMode", false);
        vol = prefs.getBoolean("soundVolume", true);
        vt = Double.longBitsToDouble(prefs.getLong("customVt", Double.doubleToLongBits(60)));
        invalidateColorCache();
    }

    private void saveState() {
        prefs.edit()
            .putString("liters", convertLitersToString(liters))
            .putString("unit", unit)
            .putInt("textSize", txtSize)
            .putInt("itemMargin", margin)
            .putInt("bgColor", bg)
            .putInt("txtColor", txt)
            .putBoolean("enableRow", enableRow)
            .putInt("refreshMs", refresh)
            .putBoolean("antiTouchMode", antiTouch)
            .putBoolean("soundVolume", vol)
            .putLong("customVt", Double.doubleToRawLongBits(vt))
            .apply();
    }

    private void parseLiters(String[] parts) {
        liters = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                liters[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                liters[i] = DEFAULT_LITERS[Math.min(i, DEFAULT_LITERS.length - 1)];
            }
        }
    }

    private void resetToDefaults() {
        liters = DEFAULT_LITERS.clone();
        unit = UNITS[0][1];
        vt = 60;
        txtSize = 32;
        margin = 5;
        bg = Color.BLACK;
        txt = Integer.MIN_VALUE;
        enableRow = true;
        refresh = 33;
        antiTouch = false;
        vol = true;
    }

    // ==================== 事件处理 ====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) {
                toggleTimer();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);

        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }

        if (flashOn && camMan != null && camId != null) {
            try {
                camMan.setTorchMode(camId, false);
            } catch (Exception ignored) {
            }
        }

        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    // ==================== 函数式接口 ====================
    @FunctionalInterface
    private interface IntConsumer {
        void accept(int value);
    }
}