package app.glasstv.guard;

// Команды «Сторожу» от своих приложений (06.11, план Codex, блок 4): тот же разбор, что у Cmd, но вход закрыт правом
// app.glasstv.permission.GUARD (только приложения с тем же ключом подписи). Cmd теперь только для adb (право DUMP)
public class AppCmd extends Cmd { }
