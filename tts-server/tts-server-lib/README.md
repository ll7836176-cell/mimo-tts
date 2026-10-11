这个目录是包装的 tts-server-go, 以方便Android调用。

# Build

```
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20220722155234-aaac322e2105
go test ./...
gomobile init

另外，还需设置ANDROID_NDK_HOME变量(如果在Android Studio里安装了NDK就直接设置ANDROID_HOME为SDK目录即可)
然后构建所有Android ABI：
gomobile bind -v -target="android" -androidapi=19

Go 1.27.2、gomobile v0.0.0-20220722155234-aaac322e2105 和 NDK 21.4.7075529 已用于当前 AAR。绑定完成后，将 tts_server_lib.aar 移动到 app/libs 目录下。
```
