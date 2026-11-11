package kz.vypuski.ar;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Экран AR: камера, метка, посадка под крестик, поправки, фиксация, снимок. */
public class ArActivity extends Activity implements GLSurfaceView.Renderer {
  private static final String TAG = "VypuskiAR";
  private static final int SRC_NONE = 0, SRC_MARKER = 1, SRC_MANUAL = 2;

  // ---------- интерфейс ----------
  private GLSurfaceView glView;
  private TextView status, info, hVal, rVal;
  private Button btnPlace, btnDir, btnLock, btnStep;
  private LinearLayout rowAdj1, rowAdj2, rowAct;
  private CrossView cross;
  private float d;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private static final float[] STEP_MM = {5, 10, 50, 1};
  private static final float[] STEP_DEG = {1, 2, 5, 0.5f};
  private int stepIdx = 0;

  // ---------- ARCore ----------
  private Session session;
  private boolean installRequested;
  private Model.Params P;
  private String markerError;
  private boolean depthOn;

  // ---------- состояние (только поток GL) ----------
  private Model model;
  private boolean modelDirty = true, flip;
  private Pose W;                 // положение модели в мире
  private Anchor anchor; private Pose rel;
  private int source = SRC_NONE;
  private boolean locked, markerDriving, dirSet, markerVisible, camTracking, hitOk;
  private float hOff, rotDeg;
  private Pose smoothMarker;
  private HitResult hit;
  private Pose camPose;
  private int frameNo;
  private boolean geomChanged = true;
  private int vw = 1, vh = 1;

  // ---------- OpenGL ----------
  private int camTex = -1, progBg, progModel;
  private int bgPos, bgUv, bgTex, mPos, mMvp, mColor;
  private final FloatBuffer ndc = fb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
  private final FloatBuffer uv = fb(new float[8]);
  private boolean uvReady;
  private final float[] proj = new float[16], view = new float[16], mm = new float[16], vp = new float[16], mvp = new float[16];

  private static FloatBuffer fb(float[] a) {
    FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    b.put(a); b.position(0); return b;
  }

  private int dp(float v) { return Math.round(v * d); }

  // =====================================================================
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    d = getResources().getDisplayMetrics().density;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    P = Model.Params.load(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));

    FrameLayout root = new FrameLayout(this);
    glView = new GLSurfaceView(this);
    glView.setPreserveEGLContextOnPause(true);
    glView.setEGLContextClientVersion(2);
    glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    glView.setRenderer(this);
    glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

    cross = new CrossView(this);
    root.addView(cross, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

    // верхняя строка
    LinearLayout top = new LinearLayout(this);
    top.setOrientation(LinearLayout.VERTICAL);
    top.setBackgroundColor(0xB0000000);
    top.setPadding(dp(12), dp(30), dp(12), dp(8));
    status = text(top, 17, true);
    info = text(top, 13, false);
    root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    // нижняя панель
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setBackgroundColor(0xC0000000);
    panel.setPadding(dp(8), dp(8), dp(8), dp(14));
    root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

    btnPlace = button("📍 Поставить сюда (под крестик)", 0xFFEECB66, Color.BLACK);
    panel.addView(btnPlace, rowLp(54));
    btnDir = button("📐 Это 2-й угол (по длине)", 0xFFEECB66, Color.BLACK);
    panel.addView(btnDir, rowLp(50));

    rowAdj1 = row(panel);
    Button up = button("▲ Поднять", 0xFFDFE6EE, Color.BLACK);
    Button down = button("▼ Опустить", 0xFFDFE6EE, Color.BLACK);
    hVal = cellText("0 мм");
    rowAdj1.addView(up, cellLp(1.3f)); rowAdj1.addView(hVal, cellLp(1f)); rowAdj1.addView(down, cellLp(1.3f));

    rowAdj2 = row(panel);
    Button rl = button("⟲", 0xFFDFE6EE, Color.BLACK);
    Button rr = button("⟳", 0xFFDFE6EE, Color.BLACK);
    rVal = cellText("0°");
    btnStep = button("шаг 5 мм / 1°", 0xFF8FA3B5, Color.BLACK);
    rowAdj2.addView(rl, cellLp(0.8f)); rowAdj2.addView(rVal, cellLp(0.8f)); rowAdj2.addView(rr, cellLp(0.8f)); rowAdj2.addView(btnStep, cellLp(1.6f));

    rowAct = row(panel);
    btnLock = button("🔒 Зафиксировать", 0xFF00C853, Color.WHITE);
    Button shot = button("📷 Снимок", 0xFFEECB66, Color.BLACK);
    rowAct.addView(btnLock, cellLp(1f)); rowAct.addView(shot, cellLp(1f));

    LinearLayout rowEnd = row(panel);
    Button reset = button("⟲ Переставить", 0xFFDFE6EE, Color.BLACK);
    Button exit = button("Выход", 0xFFDFE6EE, Color.BLACK);
    rowEnd.addView(reset, cellLp(1f)); rowEnd.addView(exit, cellLp(1f));

    btnPlace.setOnClickListener(v -> glView.queueEvent(this::placeHere));
    btnDir.setOnClickListener(v -> glView.queueEvent(this::secondCorner));
    up.setOnClickListener(v -> glView.queueEvent(() -> adjust(STEP_MM[stepIdx] / 1000f, 0)));
    down.setOnClickListener(v -> glView.queueEvent(() -> adjust(-STEP_MM[stepIdx] / 1000f, 0)));
    rl.setOnClickListener(v -> glView.queueEvent(() -> adjust(0, STEP_DEG[stepIdx])));
    rr.setOnClickListener(v -> glView.queueEvent(() -> adjust(0, -STEP_DEG[stepIdx])));
    btnStep.setOnClickListener(v -> {
      stepIdx = (stepIdx + 1) % STEP_MM.length;
      btnStep.setText("шаг " + fmt(STEP_MM[stepIdx]) + " мм / " + fmt(STEP_DEG[stepIdx]) + "°");
    });
    btnLock.setOnClickListener(v -> glView.queueEvent(this::toggleLock));
    shot.setOnClickListener(v -> takeShot());
    reset.setOnClickListener(v -> glView.queueEvent(this::resetPlacement));
    exit.setOnClickListener(v -> finish());

    setContentView(root);
    status.setText("Запуск камеры…");
    applyUi(false, false, false, "🔒 Зафиксировать", "0 мм", "0°");
  }

  private TextView text(LinearLayout parent, int size, boolean bold) {
    TextView t = new TextView(this);
    t.setTextColor(Color.WHITE); t.setTextSize(size);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    parent.addView(t);
    return t;
  }

  private TextView cellText(String s) {
    TextView t = new TextView(this);
    t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(17); t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER);
    return t;
  }

  private Button button(String s, int bgColor, int fg) {
    Button b = new Button(this);
    b.setText(s); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(fg);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(bgColor); bg.setCornerRadius(dp(10));
    b.setBackground(bg); b.setPadding(dp(4), 0, dp(4), 0);
    return b;
  }

  private LinearLayout row(LinearLayout panel) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    panel.addView(r, rowLp(50));
    return r;
  }

  private LinearLayout.LayoutParams rowLp(int h) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(h));
    lp.topMargin = dp(6);
    return lp;
  }

  private LinearLayout.LayoutParams cellLp(float w) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
    lp.leftMargin = dp(3); lp.rightMargin = dp(3);
    return lp;
  }

  private static String fmt(float v) {
    return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(v).replace('.', ',');
  }

  // =====================================================================
  @Override
  protected void onResume() {
    super.onResume();
    if (session == null) {
      try {
        ArCoreApk.InstallStatus st = ArCoreApk.getInstance().requestInstall(this, !installRequested);
        if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested = true; return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
          requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
          return;
        }
        session = new Session(this);
        Config c = new Config(session);
        c.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
        c.setFocusMode(Config.FocusMode.AUTO);
        c.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) { c.setDepthMode(Config.DepthMode.AUTOMATIC); depthOn = true; }
        try {
          InputStream is = getAssets().open("marker.png");
          Bitmap bmp = BitmapFactory.decodeStream(is);
          is.close();
          AugmentedImageDatabase db = new AugmentedImageDatabase(session);
          db.addImage("marker", bmp, P.markerW);
          c.setAugmentedImageDatabase(db);
        } catch (Throwable e) {
          markerError = "Метка недоступна: " + e.getClass().getSimpleName();
          Log.e(TAG, "marker", e);
        }
        session.configure(c);
      } catch (Throwable e) {
        Log.e(TAG, "session", e);
        status.setText("Не удалось запустить AR");
        info.setText(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")
            + "\nПроверьте «Сервисы Google Play для AR» в Play Маркете.");
        session = null;
        return;
      }
    }
    try {
      session.resume();
    } catch (Throwable e) {
      status.setText("Камера занята другим приложением — закройте его и откройте снова");
      session = null;
      return;
    }
    glView.onResume();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (session != null) { glView.onPause(); session.pause(); }
  }

  @Override
  protected void onDestroy() {
    if (session != null) { session.close(); session = null; }
    super.onDestroy();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Toast.makeText(this, "Без доступа к камере AR не работает", Toast.LENGTH_LONG).show();
      finish();
    }
  }

  // =====================================================================
  // Действия (выполняются в потоке GL)
  private static Pose rotY(float deg) {
    double a = Math.toRadians(deg) / 2;
    return Pose.makeRotation(0, (float) Math.sin(a), 0, (float) Math.cos(a));
  }

  private static Pose yawPose(float[] t, float thetaRad) {
    return new Pose(t, new float[]{0, (float) Math.sin(thetaRad / 2), 0, (float) Math.cos(thetaRad / 2)});
  }

  private void dropAnchor() {
    if (anchor != null) { anchor.detach(); anchor = null; }
    rel = null;
  }

  private void attachAnchor(Anchor a) {
    dropAnchor();
    if (a == null || W == null) return;
    anchor = a;
    rel = a.getPose().inverse().compose(W);
  }

  private void attachAnchorAtW() {
    if (W == null || session == null) return;
    try { attachAnchor(session.createAnchor(W)); } catch (Throwable e) { Log.w(TAG, "anchor", e); }
  }

  private void toast(final String s) {
    ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
  }

  private void placeHere() {
    if (hit == null || camPose == null) { toast("Крестик не видит поверхность — медленно поводите телефоном"); return; }
    float[] z = camPose.getZAxis();
    float fx = -z[0], fz = -z[2];
    double len = Math.hypot(fx, fz);
    if (len < 1e-4) { fx = 0; fz = -1; } else { fx /= len; fz /= len; }
    float theta = (float) Math.atan2(-fx, -fz);      // длина — вправо, ширина — от пользователя
    source = SRC_MANUAL; locked = false; dirSet = false; markerDriving = false;
    hOff = 0; rotDeg = 0;
    if (flip) { flip = false; modelDirty = true; }
    W = yawPose(hit.getHitPose().getTranslation(), theta);
    try { attachAnchor(hit.createAnchor()); } catch (Throwable e) { dropAnchor(); }
    toast("Поставлено");
  }

  private void secondCorner() {
    if (W == null || source != SRC_MANUAL || camPose == null) return;
    if (hit == null) { toast("Крестик не видит поверхность"); return; }
    float[] p1 = W.getTranslation(), p2 = hit.getHitPose().getTranslation();
    float dx = p2[0] - p1[0], dz = p2[2] - p1[2];
    float len = (float) Math.hypot(dx, dz);
    if (len < 0.2f) { toast("Второй угол слишком близко к первому"); return; }
    dx /= len; dz /= len;
    float theta = (float) Math.atan2(-dz, dx);        // ось длины — по линии «угол 1 → угол 2»
    float wx = (float) -Math.sin(theta), wz = (float) -Math.cos(theta);   // куда ляжет ширина
    float cx = camPose.tx() - p1[0], cz = camPose.tz() - p1[2];
    boolean f = cx * wx + cz * wz > 0;                 // пользователь с этой стороны → кладём в другую
    if (f != flip) { flip = f; modelDirty = true; }
    W = yawPose(p1, theta);
    rotDeg = 0; dirSet = true;
    if (anchor != null) rel = anchor.getPose().inverse().compose(W); else attachAnchorAtW();
    int diff = Math.round((len - P.mainL()) * 1000);
    toast(Math.abs(diff) <= 30 ? "Направление задано"
        : String.format(Locale.US, "Между углами %.2f м, длина по проекту %.2f м", len, P.mainL()));
  }

  private void adjust(float dh, float drot) {
    if (W == null) return;
    hOff += dh; rotDeg += drot;
    if (!markerDriving) {
      W = Pose.makeTranslation(0, dh, 0).compose(W).compose(rotY(drot));
      if (anchor != null) rel = anchor.getPose().inverse().compose(W);
    }
  }

  private void toggleLock() {
    if (W == null) return;
    locked = !locked;
    if (locked) { dropAnchor(); markerDriving = false; toast("Зафиксировано"); }
    else { attachAnchorAtW(); toast("Откреплено"); }
  }

  private void resetPlacement() {
    dropAnchor();
    W = null; source = SRC_NONE; locked = false; markerDriving = false; dirSet = false;
    hOff = 0; rotDeg = 0; smoothMarker = null;
    if (flip) { flip = false; modelDirty = true; }
  }

  // =====================================================================
  // OpenGL
  private static int shader(int type, String src) {
    int s = GLES20.glCreateShader(type);
    GLES20.glShaderSource(s, src);
    GLES20.glCompileShader(s);
    int[] ok = new int[1];
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s));
    return s;
  }

  private static int program(String vs, String fs) {
    int p = GLES20.glCreateProgram();
    GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
    GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
    GLES20.glLinkProgram(p);
    return p;
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f);
    int[] t = new int[1];
    GLES20.glGenTextures(1, t, 0);
    camTex = t[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

    progBg = program(
        "attribute vec4 a_Pos; attribute vec2 a_Uv; varying vec2 v_Uv; void main(){ gl_Position = a_Pos; v_Uv = a_Uv; }",
        "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_Uv; uniform samplerExternalOES u_Tex;"
            + " void main(){ gl_FragColor = texture2D(u_Tex, v_Uv); }");
    bgPos = GLES20.glGetAttribLocation(progBg, "a_Pos");
    bgUv = GLES20.glGetAttribLocation(progBg, "a_Uv");
    bgTex = GLES20.glGetUniformLocation(progBg, "u_Tex");

    progModel = program(
        "uniform mat4 u_Mvp; attribute vec3 a_Pos; void main(){ gl_Position = u_Mvp * vec4(a_Pos, 1.0); }",
        "precision mediump float; uniform vec4 u_Color; void main(){ gl_FragColor = u_Color; }");
    mPos = GLES20.glGetAttribLocation(progModel, "a_Pos");
    mMvp = GLES20.glGetUniformLocation(progModel, "u_Mvp");
    mColor = GLES20.glGetUniformLocation(progModel, "u_Color");
    uvReady = false;
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    vw = width; vh = height;
    GLES20.glViewport(0, 0, width, height);
    geomChanged = true;
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    Session s = session;
    if (s == null || camTex < 0) return;
    try {
      if (geomChanged) {
        s.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), vw, vh);
        geomChanged = false;
      }
      s.setCameraTextureName(camTex);
      Frame frame = s.update();
      Camera cam = frame.getCamera();

      // --- фон: кадр камеры ---
      if (frame.hasDisplayGeometryChanged() || !uvReady) {
        ndc.position(0); uv.position(0);
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv);
        uvReady = true;
      }
      if (frame.getTimestamp() != 0) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(progBg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex);
        GLES20.glUniform1i(bgTex, 0);
        ndc.position(0); uv.position(0);
        GLES20.glEnableVertexAttribArray(bgPos);
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, ndc);
        GLES20.glEnableVertexAttribArray(bgUv);
        GLES20.glVertexAttribPointer(bgUv, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(bgPos);
        GLES20.glDisableVertexAttribArray(bgUv);
      }

      camTracking = cam.getTrackingState() == TrackingState.TRACKING;
      camPose = cam.getDisplayOrientedPose();

      // --- точка под крестиком ---
      hit = null;
      if (camTracking) {
        for (HitResult h : frame.hitTest(vw / 2f, vh / 2f)) {
          Trackable t = h.getTrackable();
          boolean ok = (t instanceof Plane && ((Plane) t).isPoseInPolygon(h.getHitPose()))
              || t instanceof DepthPoint
              || (t instanceof Point && ((Point) t).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL);
          if (ok) { hit = h; break; }
        }
      }
      hitOk = hit != null;

      // --- метка ---
      Pose mk = null; float ex = 0, ez = 0;
      for (AugmentedImage img : s.getAllTrackables(AugmentedImage.class)) {
        if (img.getTrackingState() == TrackingState.TRACKING && img.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
          mk = img.getCenterPose(); ex = img.getExtentX(); ez = img.getExtentZ();
        }
      }
      markerVisible = mk != null;
      if (mk != null && source != SRC_MANUAL && !locked) {
        if (smoothMarker == null || dist(smoothMarker, mk) > 0.05f) smoothMarker = mk;
        else smoothMarker = Pose.makeInterpolated(smoothMarker, mk, 0.25f);
        // угол метки со стрелками — левый нижний: (−ex/2, 0, +ez/2) в системе метки
        Pose base = P.corner ? smoothMarker.compose(Pose.makeTranslation(-ex / 2, 0, ez / 2)) : smoothMarker;
        W = base.compose(Pose.makeTranslation(0, hOff, 0)).compose(rotY(rotDeg));
        if (flip) { flip = false; modelDirty = true; }
        if (!markerDriving) dropAnchor();
        markerDriving = true; source = SRC_MARKER;
      } else {
        if (markerDriving) {             // метка ушла из кадра — держим по якорю
          markerDriving = false;
          if (W != null && !locked) attachAnchorAtW();
        }
        if (anchor != null && rel != null && anchor.getTrackingState() == TrackingState.TRACKING) W = anchor.getPose().compose(rel);
      }

      // --- модель ---
      if (W != null && camTracking) {
        if (model == null || modelDirty) { model = new Model(P, flip); modelDirty = false; }
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f);
        cam.getViewMatrix(view, 0);
        W.toMatrix(mm, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, mm, 0);
        GLES20.glUseProgram(progModel);
        GLES20.glUniformMatrix4fv(mMvp, 1, false, mvp, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnableVertexAttribArray(mPos);
        draw(model.solidF, model.nSolidF, 1f, 0.88f, 0.30f, 0.10f);
        draw(model.solidC, model.nSolidC, 1f, 0.88f, 0.30f, 0.14f);
        draw(model.band, model.nBand, 0f, 0.90f, 0.46f, 0.35f);
        draw(model.cover, model.nCover, 0f, 0.90f, 0.46f, 1f);
        draw(model.edge, model.nEdge, 1f, 0.84f, 0f, 1f);
        draw(model.pin, model.nPin, 1f, 0.24f, 0f, 1f);
        GLES20.glDisableVertexAttribArray(mPos);
        GLES20.glDisable(GLES20.GL_BLEND);
      }

      if (++frameNo % 8 == 0) postUi();
    } catch (Throwable e) {
      Log.e(TAG, "frame", e);
    }
  }

  private static float dist(Pose a, Pose b) {
    float x = a.tx() - b.tx(), y = a.ty() - b.ty(), z = a.tz() - b.tz();
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  private void draw(FloatBuffer buf, int n, float r, float g, float b, float a) {
    if (n <= 0) return;
    buf.position(0);
    GLES20.glVertexAttribPointer(mPos, 3, GLES20.GL_FLOAT, false, 0, buf);
    GLES20.glUniform4f(mColor, r, g, b, a);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n);
  }

  // =====================================================================
  private void postUi() {
    final boolean placed = W != null;
    final String st, inf;
    if (!camTracking) {
      st = "Телефон ищет своё положение…";
      inf = "Медленно поводите камерой по предметам вокруг. Нужен свет и детали в кадре.";
    } else if (!placed) {
      st = markerError == null ? "Наведите камеру на метку" : "Наведите крестик на угол и нажмите «Поставить»";
      inf = (markerError == null ? "Или без метки: крестик на нужную точку → «Поставить сюда». " : markerError + ". ")
          + (hitOk ? "Поверхность под крестиком найдена ✓" : "Поверхность под крестиком пока не найдена");
    } else if (locked) {
      st = "Зафиксировано 🔒";
      inf = "Модель закреплена. ▲▼ и ⟲⟳ работают.";
    } else if (source == SRC_MARKER) {
      st = markerDriving ? "По метке ✓" : "По метке (метка не в кадре)";
      inf = markerDriving ? "Метка видна — положение уточняется. Проверьте и нажмите «Зафиксировать»."
          : "Держится по памяти телефона. Взгляните на метку, чтобы уточнить.";
    } else if (P.corner && !dirSet) {
      st = "Угол 1 поставлен ✓";
      inf = "Наведите крестик на 2-й угол по длине → «📐». Или поверните вручную ⟲⟳.";
    } else {
      st = "Поставлено ✓";
      inf = "Проверьте положение и нажмите «Зафиксировать».";
    }
    final boolean showDir = placed && source == SRC_MANUAL && P.corner && !dirSet && !locked;
    final String lockTxt = locked ? "🔓 Открепить" : "🔒 Зафиксировать";
    int h = Math.round(hOff * 1000);
    final String hTxt = (h > 0 ? "+" : "") + h + " мм";
    final String rTxt = fmt(Math.round(rotDeg * 10) / 10f) + "°";
    final boolean ok = hitOk;
    ui.post(() -> {
      status.setText(st); info.setText(inf);
      cross.setOk(ok);
      applyUi(placed, showDir, locked, lockTxt, hTxt, rTxt);
    });
  }

  private void applyUi(boolean placed, boolean showDir, boolean isLocked, String lockTxt, String hTxt, String rTxt) {
    btnPlace.setVisibility(placed ? View.GONE : View.VISIBLE);
    btnDir.setVisibility(showDir ? View.VISIBLE : View.GONE);
    int v = placed ? View.VISIBLE : View.GONE;
    rowAdj1.setVisibility(v); rowAdj2.setVisibility(v); rowAct.setVisibility(v);
    btnLock.setText(lockTxt);
    GradientDrawable bg = new GradientDrawable(); bg.setColor(isLocked ? 0xFF546E7A : 0xFF00C853); bg.setCornerRadius(dp(10));
    btnLock.setBackground(bg);
    hVal.setText(hTxt); rVal.setText(rTxt);
    cross.setVisibility(!placed || showDir ? View.VISIBLE : View.INVISIBLE);
  }

  // =====================================================================
  // Снимок: кадр камеры с моделью + штамп
  private void takeShot() {
    if (glView.getWidth() == 0) return;
    final Bitmap bmp = Bitmap.createBitmap(glView.getWidth(), glView.getHeight(), Bitmap.Config.ARGB_8888);
    final String src = source == SRC_MARKER ? "по метке" : source == SRC_MANUAL ? (dirSet ? "по двум углам" : "по точке") : "—";
    final String adj = String.format(Locale.US, "высота %+d мм, поворот %s°", Math.round(hOff * 1000), fmt(Math.round(rotDeg * 10) / 10f));
    final boolean lk = locked;
    PixelCopy.request(glView, bmp, result -> {
      if (result != PixelCopy.SUCCESS) { Toast.makeText(this, "Не удалось снять кадр", Toast.LENGTH_SHORT).show(); return; }
      List<String> lines = new ArrayList<>();
      lines.add("Выпуски AR v" + MainActivity.VERSION + " · " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(new Date()));
      if (P.hasF()) lines.add(String.format(Locale.US, "Фундамент %d×%d×%d мм, защитный слой %d мм", Math.round(P.fL * 1000), Math.round(P.fW * 1000), Math.round(P.fH * 1000), Math.round(P.fCover * 1000)));
      if (P.hasC()) lines.add(String.format(Locale.US, "Колонна %d×%d×%d мм, защитный слой %d ±%d мм", Math.round(P.cL * 1000), Math.round(P.cW * 1000), Math.round(P.cH * 1000), Math.round(P.cCover * 1000), Math.round(P.tol * 1000)));
      lines.add("Установка: " + src + ", " + adj + (lk ? ", зафиксировано" : ""));
      Canvas c = new Canvas(bmp);
      float fs = bmp.getWidth() / 34f, lh = fs * 1.35f, pad = fs * 0.6f;
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setTextSize(fs);
      float bh = lines.size() * lh + pad * 1.5f, y0 = bmp.getHeight() - bh - pad;
      p.setColor(0xB0000000);
      c.drawRect(pad, y0, bmp.getWidth() - pad, y0 + bh, p);
      for (int i = 0; i < lines.size(); i++) {
        p.setColor(i == 0 ? 0xFFFFC400 : Color.WHITE);
        p.setTypeface(i == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(lines.get(i), pad * 1.8f, y0 + pad + fs + i * lh - fs * 0.15f, p);
      }
      saveBitmap(bmp);
    }, ui);
  }

  private void saveBitmap(Bitmap bmp) {
    String name = "vypuski_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".jpg";
    try {
      ContentValues v = new ContentValues();
      v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
      v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VypuskiAR");
      Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
      if (uri == null) throw new IllegalStateException("нет доступа к галерее");
      OutputStream os = getContentResolver().openOutputStream(uri);
      bmp.compress(Bitmap.CompressFormat.JPEG, 92, os);
      if (os != null) os.close();
      Toast.makeText(this, "Снимок сохранён: Галерея → VypuskiAR", Toast.LENGTH_SHORT).show();
    } catch (Throwable e) {
      Log.e(TAG, "save", e);
      Toast.makeText(this, "Не удалось сохранить снимок: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  // =====================================================================
  /** Крестик в центре экрана: белый — поверхность найдена, красный — нет. */
  static final class CrossView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ok;

    CrossView(Context c) { super(c); p.setStyle(Paint.Style.STROKE); }

    void setOk(boolean v) { if (v != ok) { ok = v; invalidate(); } }

    @Override
    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = w * 0.3f;
      for (int pass = 0; pass < 2; pass++) {
        p.setColor(pass == 0 ? 0x90000000 : (ok ? Color.WHITE : 0xFFFF5252));
        p.setStrokeWidth(pass == 0 ? w * 0.09f : w * 0.045f);
        c.drawCircle(cx, cy, r, p);
        c.drawLine(cx, 0, cx, h, p);
        c.drawLine(0, cy, w, cy, p);
      }
    }
  }
}
