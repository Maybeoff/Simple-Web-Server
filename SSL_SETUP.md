# SSL Setup Guide

## Создание Java Keystore из сертификатов

### Если у тебя есть .pem файлы (от Let's Encrypt или другого CA)

1. Конвертируй PEM в PKCS12:
```bash
openssl pkcs12 -export \
  -in fullchain.pem \
  -inkey privkey.pem \
  -out keystore.p12 \
  -name server \
  -password pass:changeit
```

2. Конвертируй PKCS12 в JKS:
```bash
keytool -importkeystore \
  -srckeystore keystore.p12 \
  -srcstoretype PKCS12 \
  -srcstorepass changeit \
  -destkeystore keystore.jks \
  -deststoretype JKS \
  -deststorepass changeit
```

### Если нужен самоподписанный сертификат (для тестов)

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore keystore.jks \
  -storepass changeit \
  -dname "CN=localhost, OU=Dev, O=Company, L=City, ST=State, C=US"
```

## Конфигурация сервера

Добавь в `server.conf`:

```nginx
listen 443;
host 0.0.0.0;

# Enable SSL
ssl on;
ssl_keystore keystore.jks;
ssl_keystore_password changeit;

server {
    root public;
}
```

## Структура файлов

```
.
├── server.jar
├── server.conf
├── keystore.jks          # Твой keystore
└── public/
    └── index.html
```

## Запуск

```bash
java -jar server.jar
```

Сервер запустится на `https://0.0.0.0:443`

## Важно

- Храни `keystore.jks` в безопасности
- Не коммить пароли в git
- Для продакшена используй сертификаты от Let's Encrypt
- Самоподписанные сертификаты вызовут предупреждение в браузере

## Обновление сертификатов

Когда сертификат истекает, просто пересоздай keystore с новыми сертификатами и перезапусти сервер.
