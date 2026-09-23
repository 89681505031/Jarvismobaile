# Jarvis Mobile

Android-приложение J.A.R.V.I.S. Исходный проект находится в `mobile_fixed.zip`; workflow применяет исправления из `patches/` и `scripts/` перед сборкой.

## Облачная память Redis

Опциональная память подключается через отдельный серверный шлюз. В APK добавлены настройки подключения и отключения; без сервиса используется локальная память.

[Настройка Redis Agent Memory и шлюза](memory-gateway/README.md).

Сборка APK запускается вручную в Actions → Build Jarvis Android APK для ветки `fix-microphone-sensitivity`.
