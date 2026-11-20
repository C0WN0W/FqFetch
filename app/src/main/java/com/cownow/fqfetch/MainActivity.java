package com.cownow.fqfetch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设置页（Material 3 Expressive）。
 * <p>
 * 所有改动即时写入模块 SharedPreferences，宿主进程通过 XSharedPreferences 读取，
 * 无需重启宿主 App 之外的额外操作。
 */
public final class MainActivity extends Activity {

    private Prefs.Snap snap;

    private TextView tvSaved;
    private TextView tvBatch;
    private TextView tvParallel;
    private TextView tvWave;
    private TextView tvAlpha;

    private Slider sliderBatch;
    private Slider sliderParallel;
    private Slider sliderWave;
    private Slider sliderAlpha;

    private TextInputEditText etOutDir;

    private MaterialSwitch swNameAuthor;
    private MaterialSwitch swStripTitle;
    private MaterialSwitch swHtmlPlain;
    private MaterialSwitch swFileLog;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::save;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 动态取色必须在 super.onCreate 之前套用
        try {
            DynamicColors.applyToActivityIfAvailable(this);
        } catch (Throwable t) {
            L.i("动态取色不可用: " + D.err(t));
        }
        super.onCreate(savedInstanceState);
        // targetSdk 37 在 Android 15+ 会被强制边到边，低版本默认不吃 inset；
        // 这里统一关掉 decorFitsSystemWindows，让自绘 padding 在 API 31 与 35+ 表现一致。
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        } catch (Throwable t) {
            L.i("setDecorFitsSystemWindows 不可用: " + D.err(t));
        }
        setContentView(R.layout.activity_main);

        bindInsets();
        bindViews();
        bindInfo();

        snap = Prefs.readLocal(this);
        render();
        bindListeners();

        L.i("设置页打开 " + getPackageName());
    }

    @Override
    protected void onPause() {
        // 输入框里还没丢焦的内容也一起落盘
        save();
        super.onPause();
    }

    // ---------------- 视图 ----------------

    private void bindInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void bindViews() {
        tvSaved = findViewById(R.id.tv_saved);
        tvBatch = findViewById(R.id.tv_batch_value);
        tvParallel = findViewById(R.id.tv_parallel_value);
        tvWave = findViewById(R.id.tv_wave_value);
        tvAlpha = findViewById(R.id.tv_alpha_value);

        sliderBatch = findViewById(R.id.slider_batch);
        sliderParallel = findViewById(R.id.slider_parallel);
        sliderWave = findViewById(R.id.slider_wave);
        sliderAlpha = findViewById(R.id.slider_alpha);

        etOutDir = findViewById(R.id.et_out_dir);

        swNameAuthor = findViewById(R.id.sw_name_author);
        swStripTitle = findViewById(R.id.sw_strip_title);
        swHtmlPlain = findViewById(R.id.sw_html_plain);
        swFileLog = findViewById(R.id.sw_file_log);
    }

    private void bindInfo() {
        TextView info = findViewById(R.id.tv_info);
        TextView paths = findViewById(R.id.tv_paths);
        info.setText("模块版本 v" + versionName() + " · 作用域 " + Hosts.TARGET_PKG + "（番茄免费小说）\n"
                + "LSPosed 启用并勾选作用域后强制停止番茄；打开任意一本书的阅读页，"
                + "点右上角圆环悬浮窗开始整本导出。");
        paths.setText("自动探测顺序（取第一个可写目录）：\n" + Cfg.describeCandidates());
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------------- 渲染 / 采集 ----------------

    private void render() {
        binding = true;
        applyDefaultsIfNeeded();

        sliderBatch.setValue(snap.batchSize);
        sliderParallel.setValue(snap.parallel);
        sliderWave.setValue(step(snap.waveSize, 10));
        sliderAlpha.setValue(step(Math.round(snap.buttonAlpha * 100f), 5));

        etOutDir.setText(snap.outDir);
        swNameAuthor.setChecked(snap.nameWithAuthor);
        swStripTitle.setChecked(snap.stripTitle);
        swHtmlPlain.setChecked(snap.htmlPlain);
        swFileLog.setChecked(snap.fileLog);

        updateLabels();
        binding = false;
    }

    /** 兼容手改 prefs 写进来的非步长值，避免 Slider.setValue 抛异常。 */
    private void applyDefaultsIfNeeded() {
        snap.batchSize = Prefs.clamp(snap.batchSize, Prefs.MIN_BATCH, Prefs.MAX_BATCH);
        snap.parallel = Prefs.clamp(snap.parallel, Prefs.MIN_PARALLEL, Prefs.MAX_PARALLEL);
        snap.waveSize = Prefs.clamp(snap.waveSize, Prefs.MIN_WAVE, Prefs.MAX_WAVE);
        snap.buttonAlpha = Prefs.clampF(snap.buttonAlpha, Prefs.MIN_ALPHA, Prefs.MAX_ALPHA);
    }

    private static float step(float value, float step) {
        return Math.round(value / step) * step;
    }

    private void updateLabels() {
        tvBatch.setText(((int) sliderBatch.getValue()) + " 章");
        tvParallel.setText(((int) sliderParallel.getValue()) + " 路");
        tvWave.setText(((int) sliderWave.getValue()) + " 章");
        tvAlpha.setText(((int) sliderAlpha.getValue()) + "%");
    }

    private void bindListeners() {
        sliderBatch.addOnChangeListener((s, value, fromUser) -> {
            updateLabels();
            if (!binding) {
                scheduleSave();
            }
        });
        sliderParallel.addOnChangeListener((s, value, fromUser) -> {
            updateLabels();
            if (!binding) {
                scheduleSave();
            }
        });
        sliderWave.addOnChangeListener((s, value, fromUser) -> {
            updateLabels();
            if (!binding) {
                scheduleSave();
            }
        });
        sliderAlpha.addOnChangeListener((s, value, fromUser) -> {
            updateLabels();
            if (!binding) {
                scheduleSave();
            }
        });

        swNameAuthor.setOnCheckedChangeListener((b, checked) -> {
            if (!binding) {
                scheduleSave();
            }
        });
        swStripTitle.setOnCheckedChangeListener((b, checked) -> {
            if (!binding) {
                scheduleSave();
            }
        });
        swHtmlPlain.setOnCheckedChangeListener((b, checked) -> {
            if (!binding) {
                scheduleSave();
            }
        });
        swFileLog.setOnCheckedChangeListener((b, checked) -> {
            if (!binding) {
                scheduleSave();
            }
        });

        etOutDir.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!binding) {
                    scheduleSave();
                }
            }
        });

        MaterialButton reset = findViewById(R.id.btn_reset);
        reset.setOnClickListener(v -> {
            snap = new Prefs.Snap();
            render();
            save();
            toast("已恢复默认");
        });

        MaterialButton lsposed = findViewById(R.id.btn_lsposed);
        lsposed.setOnClickListener(v -> openLsposed());
    }

    private void scheduleSave() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 250L);
    }

    private void save() {
        main.removeCallbacks(saveTask);
        if (snap == null) {
            return;
        }
        snap.batchSize = (int) sliderBatch.getValue();
        snap.parallel = (int) sliderParallel.getValue();
        snap.waveSize = (int) sliderWave.getValue();
        snap.buttonAlpha = sliderAlpha.getValue() / 100f;
        Editable text = etOutDir.getText();
        snap.outDir = text == null ? "" : text.toString().trim();
        snap.nameWithAuthor = swNameAuthor.isChecked();
        snap.stripTitle = swStripTitle.isChecked();
        snap.htmlPlain = swHtmlPlain.isChecked();
        snap.fileLog = swFileLog.isChecked();

        Prefs.save(this, snap);

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        if (tvSaved != null) {
            tvSaved.setText(getString(R.string.status_saved) + " · " + time);
        }
    }

    private void openLsposed() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
        if (intent == null) {
            toast(getString(R.string.toast_lsposed_missing));
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_lsposed_missing));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
