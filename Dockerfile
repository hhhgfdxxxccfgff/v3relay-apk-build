FROM eclipse-temurin:21-jdk AS builder
ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV KEYSTORE_PATH=/src/my-upload-key.p12
ENV STORE_PASSWORD=v3relay-upload-2026
ENV KEY_PASSWORD=v3relay-upload-2026
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends unzip curl ca-certificates > /dev/null && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/licenses && \
    printf "8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n" > $ANDROID_HOME/licenses/android-sdk-license && \
    printf "84831b9409646a918e30573bab4c9c91346d8abd\n" > $ANDROID_HOME/licenses/android-sdk-preview-license
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew assembleDebug assembleRelease --no-daemon --console=plain

FROM nginx:alpine
COPY --from=builder /src/app/build/outputs/apk/debug/app-debug.apk /usr/share/nginx/html/app-debug.apk
COPY --from=builder /src/app/build/outputs/apk/release/app-release.apk /usr/share/nginx/html/app-release.apk
RUN printf '%s' '<!doctype html><html><head><meta charset="utf-8"><title>V3 Relay APK</title></head><body><h1>V3 Relay APK</h1><p><a href="app-release.apk">app-release.apk (locked)</a></p><p><a href="app-debug.apk">app-debug.apk (debug)</a></p></body></html>' > /usr/share/nginx/html/index.html
EXPOSE 80
