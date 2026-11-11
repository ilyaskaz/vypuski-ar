package kz.vypuski.ar;

import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Геометрия фундамента и колонны (пьедестала) в локальной системе модели.
 * Начало координат — точка установки (угол или центр) на уровне низа фундамента.
 * Ось X — по длине, ось −Z — по ширине, ось Y — вверх. Единицы — метры.
 */
final class Model {

  /** Параметры из формы. */
  static final class Params {
    int mode;            // 0 — фундамент + колонна, 1 — только фундамент, 2 — только колонна
    float fL, fW, fH, fCover;
    float cL, cW, cH, cCover, cDx, cDy;
    float tol;
    boolean corner;      // true — в точку ставится угол, false — центр
    float markerW;       // ширина печатной метки, м

    boolean hasF() { return mode != 2; }
    boolean hasC() { return mode != 1; }

    static Params load(SharedPreferences sp) {
      Params p = new Params();
      p.mode = sp.getInt("mode", 0);
      p.fL = mm(sp, "fL", "2000"); p.fW = mm(sp, "fW", "2000"); p.fH = mm(sp, "fH", "1000"); p.fCover = mm(sp, "fCover", "50");
      p.cL = mm(sp, "cL", "800"); p.cW = mm(sp, "cW", "800"); p.cH = mm(sp, "cH", "2000"); p.cCover = mm(sp, "cCover", "40");
      p.cDx = mm(sp, "cDx", "0"); p.cDy = mm(sp, "cDy", "0");
      p.tol = mm(sp, "tol", "5");
      p.corner = sp.getInt("anchor", 0) == 0;
      p.markerW = mm(sp, "markerW", "150");
      return p;
    }

    private static float mm(SharedPreferences sp, String key, String def) {
      return parse(sp.getString(key, def)) / 1000f;
    }

    /** Проверка данных. Возвращает текст ошибки или null. */
    String validate() {
      if (hasF() && !(fL > 0 && fW > 0 && fH > 0)) return "Заполните длину, ширину и высоту фундамента";
      if (hasC() && !(cL > 0 && cW > 0 && cH > 0)) return "Заполните длину, ширину и высоту колонны";
      if (hasF() && !(fCover >= 0)) return "Заполните защитный слой фундамента";
      if (hasC() && !(cCover >= 0)) return "Заполните защитный слой колонны";
      if (!(markerW >= 0.05f && markerW <= 1f)) return "Размер метки должен быть от 50 до 1000 мм";
      return null;
    }

    float mainL() { return hasF() ? fL : cL; }
    float mainW() { return hasF() ? fW : cW; }
  }

  static float parse(String s) {
    if (s == null) return Float.NaN;
    String t = s.replace(" ", "").replace(" ", "").replace(',', '.').trim();
    if (t.isEmpty()) return Float.NaN;
    try { return Float.parseFloat(t); } catch (NumberFormatException e) { return Float.NaN; }
  }

  // ---- наборы треугольников по цветам ----
  FloatBuffer solidF, solidC, band, edge, cover, pin;
  int nSolidF, nSolidC, nBand, nEdge, nCover, nPin;

  private float pinX, pinY;

  private static final class FL {
    float[] a = new float[2048]; int n;
    void v(float x, float y, float z) {
      if (n + 3 > a.length) a = Arrays.copyOf(a, a.length * 2);
      a[n++] = x; a[n++] = y; a[n++] = z;
    }
    FloatBuffer buf() {
      FloatBuffer b = ByteBuffer.allocateDirect(Math.max(n, 3) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
      b.put(a, 0, n); b.position(0); return b;
    }
  }

  /** Параллелепипед по локальным координатам. */
  private static void box(FL o, float x0, float y0, float z0, float x1, float y1, float z1) {
    float[][] c = {
        {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
        {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
    int[] idx = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1, 3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2};
    for (int i : idx) o.v(c[i][0], c[i][1], c[i][2]);
  }

  /** Прямоугольник в плане (X — длина, Yw — ширина) → локальные x, z. */
  private void planBox(FL o, float X0, float Y0, float X1, float Y1, float y0, float y1) {
    box(o, X0 - pinX, y0, -(Y1 - pinY), X1 - pinX, y1, -(Y0 - pinY));
  }

  /** Контур прямоугольника брусками толщиной t на высоте y. */
  private void ring(FL o, float X0, float Y0, float X1, float Y1, float y, float t) {
    float h = t / 2;
    planBox(o, X0 - h, Y0 - h, X1 + h, Y0 + h, y - h, y + h);
    planBox(o, X0 - h, Y1 - h, X1 + h, Y1 + h, y - h, y + h);
    planBox(o, X0 - h, Y0 - h, X0 + h, Y1 + h, y - h, y + h);
    planBox(o, X1 - h, Y0 - h, X1 + h, Y1 + h, y - h, y + h);
  }

  private void posts(FL o, float X0, float Y0, float X1, float Y1, float y0, float y1, float t) {
    float h = t / 2;
    float[][] c = {{X0, Y0}, {X1, Y0}, {X1, Y1}, {X0, Y1}};
    for (float[] p : c) planBox(o, p[0] - h, p[1] - h, p[0] + h, p[1] + h, y0, y1);
  }

  /** Плоская рамка между двумя вложенными прямоугольниками (зона защитного слоя). */
  private void frame(FL o, float X0, float Y0, float X1, float Y1, float in0, float in1, float y) {
    float a = in0, b = in1, e = 0.0005f;
    planBox(o, X0 + a, Y0 + a, X1 - a, Y0 + b, y - e, y + e);
    planBox(o, X0 + a, Y1 - b, X1 - a, Y1 - a, y - e, y + e);
    planBox(o, X0 + a, Y0 + b, X0 + b, Y1 - b, y - e, y + e);
    planBox(o, X1 - b, Y0 + b, X1 - a, Y1 - b, y - e, y + e);
  }

  /**
   * @param flip true — точка установки в «дальнем» по ширине углу (фундамент ложится в другую сторону).
   */
  Model(Params p, boolean flip) {
    FL sF = new FL(), sC = new FL(), bd = new FL(), ed = new FL(), cv = new FL(), pn = new FL();
    float mL = p.mainL(), mW = p.mainW();
    if (p.corner) { pinX = -mL / 2; pinY = flip ? mW / 2 : -mW / 2; } else { pinX = 0; pinY = 0; }
    final float T = 0.012f, TC = 0.007f;
    float zC = 0;

    if (p.hasF()) {
      float x0 = -p.fL / 2, x1 = p.fL / 2, y0 = -p.fW / 2, y1 = p.fW / 2;
      zC = p.fH;
      planBox(sF, x0, y0, x1, y1, 0, p.fH);
      ring(ed, x0, y0, x1, y1, T / 2, T); ring(ed, x0, y0, x1, y1, p.fH, T); posts(ed, x0, y0, x1, y1, 0, p.fH, T);
      float c = p.fCover;
      if (c > 0 && 2 * c < Math.min(p.fL, Math.min(p.fW, p.fH))) {
        ring(cv, x0 + c, y0 + c, x1 - c, y1 - c, c, TC); ring(cv, x0 + c, y0 + c, x1 - c, y1 - c, p.fH - c, TC);
        posts(cv, x0 + c, y0 + c, x1 - c, y1 - c, c, p.fH - c, TC);
      }
    }
    if (p.hasC()) {
      float cx = p.hasF() ? p.cDx : 0, cy = p.hasF() ? p.cDy : 0;
      float x0 = cx - p.cL / 2, x1 = cx + p.cL / 2, y0 = cy - p.cW / 2, y1 = cy + p.cW / 2;
      float top = zC + p.cH;
      planBox(sC, x0, y0, x1, y1, zC, top);
      ring(ed, x0, y0, x1, y1, zC + T / 2, T); ring(ed, x0, y0, x1, y1, top, T); posts(ed, x0, y0, x1, y1, zC, top, T);
      float in0 = Math.max(0, p.cCover - p.tol), in1 = p.cCover + p.tol;
      if (2 * in1 < Math.min(p.cL, p.cW)) {
        float hMax = Math.min(p.cH, 1.5f);
        for (float h = 0; h <= hMax + 1e-4f; h += 0.5f) {
          float y = zC + h + 0.004f;
          if (in0 > 0) ring(cv, x0 + in0, y0 + in0, x1 - in0, y1 - in0, y, TC);
          ring(cv, x0 + in1, y0 + in1, x1 - in1, y1 - in1, y, TC);
          frame(bd, x0, y0, x1, y1, in0, in1, y);
        }
      }
    }
    // точка установки — красный крестик 12 см
    box(pn, -0.06f, 0.002f, -0.006f, 0.06f, 0.012f, 0.006f);
    box(pn, -0.006f, 0.002f, -0.06f, 0.006f, 0.012f, 0.06f);

    solidF = sF.buf(); nSolidF = sF.n / 3;
    solidC = sC.buf(); nSolidC = sC.n / 3;
    band = bd.buf(); nBand = bd.n / 3;
    edge = ed.buf(); nEdge = ed.n / 3;
    cover = cv.buf(); nCover = cv.n / 3;
    pin = pn.buf(); nPin = pn.n / 3;
  }
}
