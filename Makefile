.PHONY: build

build:
	./gradlew assembleDebug
	cp app/build/outputs/apk/debug/app-debug.apk ./app-debug.apk
