package kz.vypuski.ar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Экран ввода размеров. Координаты не вводятся. */
public class MainActivity extends Activity {
  static final String PREFS = "vypuski";
  static final String VERSION = "5.0";

  private SharedPreferences sp;
  private final List<EditText> fields = new ArrayList<>();
  private final List<String> keys = new ArrayList<>();
  private LinearLayout blockF, blockC, blockShift;
  private float d;

  private int dp(float v) { return Math.round(v * d); }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    sp = getSharedPreferences(PREFS, MODE_PRIVATE);
    d = getResources().getDisplayMetrics().density;

    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xFFEEF2F6);
    scroll.setFitsSystemWindows(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(12), dp(12), dp(24));
    scroll.addView(root);

    TextView title = new TextView(this);
    title.setText("Выпуски AR  ·  v" + VERSION);
    title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(0xFF2F6FAE);
    title.setPadding(dp(4), dp(4), dp(4), dp(10));
    root.addView(title);

    // --- режим ---
    LinearLayout cm = card(root, "Что проверяем");
    Spinner mode = spinner(cm, new String[]{"Фундамент + колонна (пьедестал)", "Только фундамент", "Только колонна (пьедестал)"}, "mode");

    // --- фундамент ---
    blockF = card(root, "Фундамент");
    row(blockF, new String[]{"Длина мм", "Ширина мм", "Высота мм", "Защ. слой мм"}, new String[]{"fL", "fW", "fH", "fCover"}, new String[]{"2000", "2000", "1000", "50"});

    // --- колонна ---
    blockC = card(root, "Колонна, пьедестал");
    row(blockC, new String[]{"Длина мм", "Ширина мм", "Высота мм", "Защ. слой мм"}, new String[]{"cL", "cW", "cH", "cCover"}, new String[]{"800", "800", "2000", "40"});
    blockShift = new LinearLayout(this);
    blockShift.setOrientation(LinearLayout.VERTICAL);
    blockC.addView(blockShift);
    note(blockShift, "Смещение колонны от центра фундамента, мм (0 — по центру)");
    row(blockShift, new String[]{"По длине", "По ширине"}, new String[]{"cDx", "cDy"}, new String[]{"0", "0"});

    // --- установка ---
    LinearLayout cs = card(root, "Установка");
    note(cs, "Что ставится в точку (под крестик или в угол метки)");
    spinner(cs, new String[]{"Угол фундамента", "Центр"}, "anchor");
    row(cs, new String[]{"Допуск защ. слоя ± мм", "Ширина метки мм"}, new String[]{"tol", "markerW"}, new String[]{"5", "150"});
    note(cs, "Метка — напечатанный лист «Метка Выпуски AR». Измерьте линейкой ширину чёрного квадрата на распечатке и впишите её сюда. "
        + "Положите метку на землю: её угол со стрелками — в угол фундамента, стрелка «длина» — вдоль длинной стороны. "
        + "Когда камера видит метку, модель встаёт по ней сама и поправляется при каждом взгляде на метку. "
        + "Без метки — кнопка «Поставить сюда» под крестиком.");

    Button start = new Button(this);
    start.setText("Открыть камеру");
    start.setTextSize(18); start.setAllCaps(false); start.setTextColor(Color.WHITE);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF2F6FAE); bg.setCornerRadius(dp(12));
    start.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
    lp.topMargin = dp(8);
    root.addView(start, lp);
    start.setOnClickListener(v -> {
      save();
      String err = Model.Params.load(sp).validate();
      if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
      startActivity(new Intent(this, ArActivity.class));
    });

    mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        sp.edit().putInt("mode", pos).apply();
        blockF.setVisibility(pos == 2 ? View.GONE : View.VISIBLE);
        blockC.setVisibility(pos == 1 ? View.GONE : View.VISIBLE);
        blockShift.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
      }
      @Override public void onNothingSelected(AdapterView<?> parent) { }
    });

    setContentView(scroll);
  }

  @Override
  protected void onPause() { super.onPause(); save(); }

  private void save() {
    SharedPreferences.Editor e = sp.edit();
    for (int i = 0; i < fields.size(); i++) e.putString(keys.get(i), fields.get(i).getText().toString());
    e.apply();
  }

  private LinearLayout card(LinearLayout root, String title) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14));
    c.setBackground(bg);
    c.setPadding(dp(12), dp(10), dp(12), dp(12));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(10);
    root.addView(c, lp);
    TextView t = new TextView(this);
    t.setText(title); t.setTextSize(16); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(Color.WHITE);
    GradientDrawable tb = new GradientDrawable(); tb.setColor(0xFF5B9BD5); tb.setCornerRadius(dp(6));
    t.setBackground(tb); t.setPadding(dp(10), dp(8), dp(10), dp(8));
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tl.bottomMargin = dp(8);
    c.addView(t, tl);
    return c;
  }

  private void note(LinearLayout parent, String text) {
    TextView t = new TextView(this);
    t.setText(text); t.setTextSize(13); t.setTextColor(0xFF5D6B78);
    t.setPadding(dp(2), dp(6), dp(2), dp(4));
    parent.addView(t);
  }

  private void row(LinearLayout parent, String[] labels, String[] ks, String[] defs) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    for (int i = 0; i < labels.length; i++) {
      LinearLayout cell = new LinearLayout(this);
      cell.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      if (i > 0) cl.leftMargin = dp(6);
      TextView l = new TextView(this);
      l.setText(labels[i]); l.setTextSize(12); l.setTextColor(0xFF2F6FAE); l.setGravity(Gravity.CENTER);
      l.setMinLines(2);
      cell.addView(l);
      EditText e = new EditText(this);
      e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
      e.setText(sp.getString(ks[i], defs[i]));
      e.setTextSize(17); e.setTypeface(Typeface.DEFAULT_BOLD); e.setGravity(Gravity.CENTER); e.setTextColor(0xFF1C2733);
      GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFEECB66); bg.setCornerRadius(dp(6));
      e.setBackground(bg); e.setPadding(dp(4), dp(10), dp(4), dp(10));
      e.setSelectAllOnFocus(true);
      cell.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      r.addView(cell, cl);
      fields.add(e); keys.add(ks[i]);
    }
    parent.addView(r);
  }

  private Spinner spinner(LinearLayout parent, String[] items, final String key) {
    Spinner s = new Spinner(this);
    ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    s.setAdapter(a);
    s.setSelection(Math.min(items.length - 1, Math.max(0, sp.getInt(key, 0))));
    s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { sp.edit().putInt(key, pos).apply(); }
      @Override public void onNothingSelected(AdapterView<?> p) { }
    });
    parent.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    return s;
  }
}
