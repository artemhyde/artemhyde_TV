package app.glasstv.home;

import android.view.Choreographer;

// Пружина в духе Apple (05.11): вместо анимации с заданной длительностью значение тянется к цели как груз на пружине.
// Два числа: затухание (1 без отскока, меньше единицы с мягким перелётом) и отклик (за сколько секунд почти доходит).
// Новая цель посреди движения не обрывает его: скорость сохраняется, поэтому при удержании стрелки сферы и ряд
// текут без рывков. Шаг считается по кадрам экрана (Choreographer), от масштаба анимаций системы не зависит
final class Spring implements Choreographer.FrameCallback {
    interface Out { void set(float v); }

    float value, velocity, target;
    private float zeta, omega;
    private final Out out;
    private boolean running;
    private long last;
    Runnable onRest;   // пружина остановилась
    float eps = 0.0005f;   // точность остановки в единицах значения (масштаб: доли, прокрутка: точки)

    Spring(float start, Out out) {
        this.value = start;
        this.target = start;
        this.out = out;
        tune(1f, 0.4f);
    }

    Spring tune(float damping, float response) {
        zeta = damping;
        omega = (float) (2 * Math.PI / Math.max(0.05f, response));
        return this;
    }

    boolean running() { return running; }

    void to(float t) {
        target = t;
        if (running) return;
        if (Math.abs(value - target) < eps && Math.abs(velocity) < eps * 10f) { value = target; velocity = 0; out.set(value); return; }
        running = true;
        last = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    // Сразу в точку, без движения
    void snap(float t) {
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        value = target = t;
        velocity = 0;
        out.set(t);
    }

    void stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        float dt = last == 0 ? 1f / 60f : Math.min(0.05f, (nanos - last) / 1e9f);
        last = nanos;
        // Мелкими шагами (до 4 мс): так пружина устойчива и при пропущенном кадре
        int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = omega * omega * (target - value) - 2f * zeta * omega * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        if (Math.abs(target - value) < eps && Math.abs(velocity) < eps * 10f) {
            value = target;
            velocity = 0;
            running = false;
            out.set(value);
            if (onRest != null) onRest.run();
            return;
        }
        out.set(value);
        Choreographer.getInstance().postFrameCallback(this);
    }
}
