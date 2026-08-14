.PHONY: build

build:
	./gradlew assembleRelease
	cp app/build/outputs/apk/release/app-release-unsigned.apk ./app-release-unsigned.apk
