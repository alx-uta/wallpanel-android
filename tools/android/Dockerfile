# Android build toolchain for WallPanel.
#
# Pins JDK 17 (matching .github/workflows/*.yml) and the exact SDK versions
# WallPanelPro/build.gradle asks for. Deliberately independent of whatever JDK
# Android Studio happens to bundle on the host.

FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        unzip \
        git \
        ca-certificates \
        jq \
    && rm -rf /var/lib/apt/lists/*

# Version of the standalone command line tools package (not the SDK itself).
ARG CMDLINE_TOOLS_VERSION=11076708

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

# compileSdk 35 / buildTools 35.0.0 come from WallPanelPro/build.gradle.
# platform-tools provides adb, which runs its own server in this container and connects
# to the network devices over TCP (see tools/android/connect-devices.sh).
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" > /dev/null

WORKDIR /project
