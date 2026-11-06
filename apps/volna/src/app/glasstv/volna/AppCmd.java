package app.glasstv.volna;

// Команды «Волне» от своих приложений (06.11, план Codex): тот же разбор, что у Cmd, но вход закрыт правом
// app.glasstv.permission.HYDE (только приложения с тем же ключом подписи). Cmd теперь только для adb (право DUMP)
public class AppCmd extends Cmd { }
