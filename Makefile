.PHONY: build release

build:
	./gradlew assembleDebug
	cp app/build/outputs/apk/debug/app-debug.apk ./app-debug.apk
	cp mobile/build/outputs/apk/debug/mobile-debug.apk ./mobile-debug.apk

release:
	./gradlew assembleRelease
	cp app/build/outputs/apk/release/app-release-unsigned.apk ./app-release-unsigned.apk
	cp mobile/build/outputs/apk/release/mobile-release-unsigned.apk ./mobile-release-unsigned.apk
