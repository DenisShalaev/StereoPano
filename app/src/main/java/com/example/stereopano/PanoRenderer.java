package com.example.stereopano;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Zeichnet das Panorama innen auf einen (Teil-)Kugelmantel. Der Betrachter sitzt im Mittelpunkt.
 *  - Mono:   linkes Bild, Vollbild
 *  - VR:     Bildschirm geteilt, linke Haelfte = linkes Auge, rechte Haelfte = rechtes Auge (Cardboard)
 * Weltkoordinaten sind Ost (x), Nord (y), Oben (z) - passend zum Rotationsvektor-Sensor.
 * Die Linsenverzerrung der Cardboard-Linsen wird hier nicht korrigiert (siehe Hinweis in der Doku).
 */
final class PanoRenderer implements GLSurfaceView.Renderer {

    private static final float RADIUS = 10f;
    private static final int N_AZ = 96, N_EL = 24;

    private static final String VS =
            "uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;"
          + "void main(){ vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }";
    private static final String FS =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uTex;"
          + "void main(){ gl_FragColor = texture2D(uTex, vUv); }";

    // --- Zustand (UI-Thread schreibt, GL-Thread liest)
    private final Object lock = new Object();
    private Bitmap pendL, pendR;
    private double pu0, pu1, pElTop, pElBot;
    private boolean pendNew;

    private volatile float[] rot;            // 3x3 (zeilenweise), Geraet -> Welt; null = nur Touch
    private volatile float headingOffset;    // Grad, dreht das Panorama um die Hochachse
    private volatile float yaw, pitch;       // Touch-Verschiebung in Grad
    static final int MODE_FLAT = 0, MODE_CARDBOARD = 1, MODE_SBS = 2;
    private volatile int mode = MODE_FLAT;
    private volatile boolean sbsHalf;        // Half-SBS: jedes Auge horizontal auf die halbe Breite gestaucht
    private volatile float fovScale = 1f;    // Pinch-Zoom

    // --- Linsenkorrektur (Cardboard): Szene -> FBO -> Fragment-Shader mit radialer Tonnenverzerrung
    private volatile float k1 = 0.34f, k2 = 0.55f;      // Cardboard-v2-aehnliche Koeffizienten (Einstellungen)
    private volatile float sbsFov = 45f;                 // vertikales Sichtfeld im SBS-Modus (Einstellungen)
    private static final float RS = 0.5f;                // Normierung des Radius
    private static final String VS2 =
            "attribute vec2 aP; varying vec2 vP; void main(){ vP = aP; gl_Position = vec4(aP, 0.0, 1.0); }";
    private static final String FS2 =
            "precision mediump float; varying vec2 vP; uniform sampler2D uT; uniform float uAsp, uK1, uK2, uFit;"
          + "void main(){ vec2 p = vec2(vP.x * uAsp, vP.y); vec2 pr = p * " + RS + "; float r2 = dot(pr, pr);"
          + " float s = (1.0 + uK1 * r2 + uK2 * r2 * r2) * uFit; vec2 q = p * s; vec2 uv = vec2(q.x / uAsp, q.y);"
          + " if (abs(uv.x) > 1.0 || abs(uv.y) > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }"
          + " gl_FragColor = texture2D(uT, uv * 0.5 + 0.5); }";
    private volatile boolean lens = true;
    private int prog2, aP2, uT2, uAsp2, uK12, uK22, uFit2;
    private int fbo, fboTex, fboW, fboH;
    private FloatBuffer quad;

    // --- GL-Objekte
    private int program, aPos, aUv, uMvp, uTex;
    private int texL, texR;
    private FloatBuffer vtx;
    private ShortBuffer idx;
    private int idxCount;
    private int width = 1, height = 1;

    private final float[] proj = new float[16], view = new float[16], model = new float[16];
    private final float[] vm = new float[16], mvp = new float[16], base = new float[16], touch = new float[16];

    // ------------------------------------------------------------------ API fuer den UI-Thread

    void setPano(Bitmap left, Bitmap right, double u0, double u1, double elTopDeg, double elBotDeg) {
        synchronized (lock) {
            pendL = left; pendR = right;
            pu0 = u0; pu1 = u1; pElTop = elTopDeg; pElBot = elBotDeg;
            pendNew = true;
        }
    }

    void setRotation(float[] r) { rot = r; }
    void setHeadingOffset(float deg) { headingOffset = deg; }
    void setMode(int m) { mode = m; }
    void setSbsHalf(boolean h) { sbsHalf = h; }
    void setLens(boolean l) { lens = l; }
    void setLensK(float a, float b) { k1 = a; k2 = b; }
    void setSbsFov(float f) { sbsFov = f; }
    void setFovScale(float s) { fovScale = Math.max(0.4f, Math.min(1.6f, s)); }
    float getFovScale() { return fovScale; }

    void drag(float dx, float dy) {
        yaw -= dx * 0.15f;
        pitch = Math.max(-85, Math.min(85, pitch - dy * 0.15f));
    }

    void resetDrag() { yaw = 0; pitch = 0; }

    // ------------------------------------------------------------------ GL

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0, 0, 0, 1);
        program = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        texL = texR = 0;
        prog2 = link(compile(GLES20.GL_VERTEX_SHADER, VS2), compile(GLES20.GL_FRAGMENT_SHADER, FS2));
        aP2 = GLES20.glGetAttribLocation(prog2, "aP");
        uT2 = GLES20.glGetUniformLocation(prog2, "uT");
        uAsp2 = GLES20.glGetUniformLocation(prog2, "uAsp");
        uK12 = GLES20.glGetUniformLocation(prog2, "uK1");
        uK22 = GLES20.glGetUniformLocation(prog2, "uK2");
        uFit2 = GLES20.glGetUniformLocation(prog2, "uFit");
        quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        fbo = fboTex = 0;   // GL-Kontext ist neu
        fboW = fboH = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        uploadPending();
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (texL == 0 || vtx == null) return;

        buildView(view);
        Matrix.setRotateM(model, 0, -headingOffset, 0, 0, 1);   // Azimut im Uhrzeigersinn -> Drehung um -Z
        Matrix.multiplyMM(vm, 0, view, 0, model, 0);

        int half = width / 2;
        switch (mode) {
            case MODE_CARDBOARD:
                if (lens) {
                    cardboardEye(0, half, texL);
                    cardboardEye(half, width - half, texR);
                } else {
                    drawEye(0, half, texL, 90f, (float) half / height);
                    drawEye(half, width - half, texR, 90f, (float) half / height);
                }
                break;
            case MODE_SBS: {
                float fov = sbsFov * fovScale;
                // Half-SBS: Bild wird fuer die volle Breite berechnet und von der Brille wieder gestreckt
                float asp = sbsHalf ? (float) width / height : (float) half / height;
                drawEye(0, half, texL, fov, asp);
                drawEye(half, width - half, texR, fov, asp);
                break;
            }
            default:
                drawEye(0, width, texL, 65f * fovScale, (float) width / height);
        }
    }

    /** Ein Auge: Szene mit groesserem Sichtfeld in den FBO, dann mit Linsenverzerrung auf den Bildschirm. */
    private void cardboardEye(int x, int w, int tex) {
        ensureFbo(w, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, w, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawEye(0, w, tex, 100f, (float) w / height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        GLES20.glViewport(x, 0, w, height);
        GLES20.glUseProgram(prog2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glUniform1i(uT2, 0);
        GLES20.glUniform1f(uAsp2, (float) w / height);
        GLES20.glUniform1f(uK12, k1);
        GLES20.glUniform1f(uK22, k2);
        GLES20.glUniform1f(uFit2, 1f / (1f + k1 * RS * RS + k2 * RS * RS * RS * RS));
        quad.position(0);
        GLES20.glVertexAttribPointer(aP2, 2, GLES20.GL_FLOAT, false, 8, quad);
        GLES20.glEnableVertexAttribArray(aP2);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aP2);
    }

    private void ensureFbo(int w, int h) {
        if (fbo != 0 && fboW == w && fboH == h) return;
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
        }
        int[] t = new int[1], f = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        fbo = f[0]; fboTex = t[0]; fboW = w; fboH = h;
    }

    private void drawEye(int x, int w, int tex, float fovY, float aspect) {
        GLES20.glViewport(x, 0, w, height);
        Matrix.perspectiveM(proj, 0, fovY, aspect, 0.1f, 100f);
        Matrix.multiplyMM(mvp, 0, proj, 0, vm, 0);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(uTex, 0);

        vtx.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vtx);
        GLES20.glEnableVertexAttribArray(aPos);
        vtx.position(3);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vtx);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, idx);
    }

    /** Sicht-Matrix: Welt (Ost, Nord, Oben) -> Kamera, plus Touch-Verschiebung. */
    private void buildView(float[] out) {
        float[] r = rot;
        Matrix.setIdentityM(base, 0);
        if (r != null) {
            // Kamera->Welt ist r, also Welt->Kamera = Transponierte (OpenGL-Matrizen sind spaltenweise)
            for (int j = 0; j < 3; j++)
                for (int i = 0; i < 3; i++)
                    base[j * 4 + i] = r[j * 3 + i];
        } else {
            // Blick nach Norden: x_cam = Ost, y_cam = Oben, z_cam = -Nord
            base[0] = 1; base[5] = 0; base[10] = 0;
            base[9] = 1;     // (Zeile 1, Spalte 2)
            base[6] = -1;    // (Zeile 2, Spalte 1)
        }
        Matrix.setRotateM(touch, 0, pitch, 1, 0, 0);
        Matrix.rotateM(touch, 0, yaw, 0, 1, 0);
        Matrix.multiplyMM(out, 0, touch, 0, base, 0);
    }

    // ------------------------------------------------------------------ Texturen / Netz

    private void uploadPending() {
        Bitmap l, r;
        double u0, u1, top, bot;
        synchronized (lock) {
            if (!pendNew) return;
            l = pendL; r = pendR; u0 = pu0; u1 = pu1; top = pElTop; bot = pElBot;
            pendL = pendR = null;
            pendNew = false;
        }
        if (texL != 0) GLES20.glDeleteTextures(1, new int[]{texL}, 0);
        if (texR != 0 && texR != texL) GLES20.glDeleteTextures(1, new int[]{texR}, 0);
        texL = loadTexture(l);
        texR = (r == l) ? texL : loadTexture(r);
        buildMesh(u0, u1, top, bot);
        if (r != l) r.recycle();
        l.recycle();   // Bitmaps liegen jetzt im GPU-Speicher
    }

    private int loadTexture(Bitmap src) {
        int[] max = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
        Bitmap bmp = src;
        int big = Math.max(src.getWidth(), src.getHeight());
        if (big > max[0]) {
            float s = (float) max[0] / big;
            bmp = Bitmap.createScaledBitmap(src, Math.max(1, (int) (src.getWidth() * s)),
                    Math.max(1, (int) (src.getHeight() * s)), true);
        }
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        if (bmp != src) bmp.recycle();
        return id[0];
    }

    /** Teilkugel: Azimut u0..u1 (Anteil von 360 Grad), Elevation von elTop bis elBot (Grad). */
    private void buildMesh(double u0, double u1, double elTop, double elBot) {
        float[] v = new float[(N_AZ + 1) * (N_EL + 1) * 5];
        int k = 0;
        for (int j = 0; j <= N_EL; j++) {
            double t = (double) j / N_EL;
            double el = Math.toRadians(elTop + (elBot - elTop) * t);
            for (int i = 0; i <= N_AZ; i++) {
                double s = (double) i / N_AZ;
                double az = 2 * Math.PI * (u0 + (u1 - u0) * s);   // im Uhrzeigersinn ab Nord
                v[k++] = (float) (RADIUS * Math.sin(az) * Math.cos(el));   // Ost
                v[k++] = (float) (RADIUS * Math.cos(az) * Math.cos(el));   // Nord
                v[k++] = (float) (RADIUS * Math.sin(el));                  // Oben
                v[k++] = (float) s;
                v[k++] = (float) t;
            }
        }
        short[] ix = new short[N_AZ * N_EL * 6];
        int m = 0;
        for (int j = 0; j < N_EL; j++) {
            for (int i = 0; i < N_AZ; i++) {
                int a = j * (N_AZ + 1) + i, b = a + 1, c = a + N_AZ + 1, d = c + 1;
                ix[m++] = (short) a; ix[m++] = (short) c; ix[m++] = (short) b;
                ix[m++] = (short) b; ix[m++] = (short) c; ix[m++] = (short) d;
            }
        }
        vtx = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vtx.put(v).position(0);
        idx = ByteBuffer.allocateDirect(ix.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        idx.put(ix).position(0);
        idxCount = ix.length;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        return p;
    }
}
