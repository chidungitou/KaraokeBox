# Khi YouTube bị hỏng: cập nhật NewPipeExtractor

YouTube hay đổi cách bảo vệ nên thư viện NewPipeExtractor thỉnh thoảng ngừng hoạt động. App tự phát hiện và báo
trên điện thoại ("YouTube tạm thời không dùng được").

**Cách nhanh nhất (v1.3.1):** trên TV vào **Cài đặt > nút "YouTube: ..."**. Màn hình đó hiện bản đang dùng, bản mới nhất,
tình trạng YouTube và các bước bên dưới kèm sẵn số phiên bản cần gõ.

## Xác nhận nguyên nhân
Trên điện thoại (cùng Wi-Fi, là **chủ phòng**) bấm **bánh răng > Xem nhật ký lỗi YouTube**, hoặc bấm **Xem nhật ký lỗi** trong màn hình trên TV. Địa chỉ trực tiếp `http://<ip-box>:8080/api/youtube/diag` từ v1.6.0 cần kèm `?cid=<mã máy chủ phòng>` nên dùng nút sẽ tiện hơn:
- Nhiều dòng `code=YT_CHANGED` hoặc `ExtractionException`: YouTube đổi dữ liệu -> cần nâng thư viện (bên dưới).
- `code=YT_NETWORK`: box mất Internet / DNS, không phải lỗi thư viện.
- `code=YT_BOT`: YouTube tạm chặn IP (thường chỉ chặn bước lấy link phát, tìm kiếm vẫn chạy). App tự nghỉ 2 → 4 → 8 → 15 → 30 phút
  giữa các lần thử (không gửi thêm request trong lúc nghỉ) và GIỮ bài đang chờ trong hàng, không bỏ qua. Nếu có bài trong máy
  trong hàng chờ, sau ~20 giây app hát bài đó trước. Muốn thử lại ngay (vd vừa reset modem để đổi IP): bấm **Thử lại** trên banner
  ở điện thoại. Bản thư viện cũ không phải nguyên nhân chính của lỗi này, nâng thư viện thường không giúp.
- `code=YT_PLAYBACK`: link lấy được nhưng phát lỗi (403...). Hay gặp khi thư viện lỗi thời.

## Cách nâng thư viện
1. Xem bản mới nhất: https://github.com/TeamNewPipe/NewPipeExtractor/releases (màn hình trong app cũng hiện sẵn).
2. Mở `gradle.properties` ở thư mục gốc, sửa DUY NHẤT dòng `newpipeVersion=v0.26.5` sang bản mới (vd `newpipeVersion=v0.27.0`).
   (Hoặc không sửa file, build bằng dòng lệnh: `gradlew assembleDebug -PnewpipeVersion=v0.27.0`.)
3. Sync Gradle, build lại APK, cài đè lên box (giữ nguyên chữ ký debug để cài đè được; dữ liệu không mất).
4. Mở app, vào Cài đặt > YouTube, bấm **Thử YouTube ngay** để kiểm tra.

## Tự build APK bằng GitHub Actions (khỏi cài Android Studio mỗi lần)
Workflow `.github/workflows/build-apk.yml` mỗi ngày hỏi GitHub xem NewPipeExtractor có bản mới không. Có thì tự build APK với bản đó và đăng lên **Releases**; không có thì bỏ qua (khoảng 1 phút).
Build với bản mới lỗi (JitPack chưa dựng xong, thư viện đổi API) thì **tự lùi về bản đang ghim** trong `gradle.properties` và thử lại hôm sau.

**Thiết lập một lần**
1. Đưa project lên một repo GitHub (nên để **private**). Workflow tự chạy sau khi bạn đẩy code; vào tab **Actions** để xem.
2. **Khoá ký (quan trọng):** Android chỉ cho cài đè khi APK mới ký bằng đúng khoá của APK đang chạy. Bạn đã build bằng Android Studio nên APK trên box đang ký bằng khoá debug trong máy bạn. Đưa khoá đó cho workflow:
   - Windows: `certutil -encode %USERPROFILE%\.android\debug.keystore ks.b64` rồi mở `ks.b64`, bỏ dòng đầu và dòng cuối (BEGIN/END), gộp phần còn lại thành một chuỗi.
   - macOS/Linux: `base64 -w0 ~/.android/debug.keystore` (macOS: `base64 -i ~/.android/debug.keystore`).
   - Trong repo: **Settings > Secrets and variables > Actions > New repository secret**, tên `DEBUG_KEYSTORE_BASE64`, dán chuỗi vừa có.
   Từ v1.12.2 workflow ký thẳng bằng file khoá này, kiểm tra lại APK sau khi build (sai khoá thì báo lỗi, không đăng release) và ghi dòng **Khoá ký APK (SHA-256)** vào ghi chú của release. Trong Cài đặt trên điện thoại, dòng **Phiên bản** có thêm **Khoá ký: AB:CD:...** (6 byte đầu); hai bên phải khớp nhau thì mới cài đè được. Khi lệch, thông báo lỗi cũng in cả hai khoá.
   Không làm bước này thì workflow vẫn build được nhưng APK ký bằng khoá tạm: lần đầu phải gỡ app cũ trên box rồi cài lại (mất hàng chờ và cài đặt), và release có ghi cảnh báo.
3. Bật tính năng tạo release: **Settings > Actions > General > Workflow permissions > Read and write permissions** (workflow đã khai báo `contents: write`, nhưng một số tổ chức khoá mặc định).

**Cài lên box**
- Dùng PC: tải `KaraokeBox.apk` từ Releases rồi `adb connect <ip-box>` và `adb install -r KaraokeBox.apk`.
- Hoặc trên box: mở trình duyệt / app Downloader, nhập `https://github.com/<tài-khoản>/<repo>/releases/latest/download/KaraokeBox.apk` (repo **public** thì tải thẳng được; repo private cần đăng nhập nên dùng PC).

**Lưu ý**
- GitHub tự tắt workflow theo lịch sau 60 ngày repo không có hoạt động. Vào tab Actions bấm bật lại, hoặc đẩy một commit nhỏ.
- Workflow thất bại sẽ có email báo từ GitHub.
- Muốn ép bản NewPipe cụ thể hoặc build lại: tab Actions > Build APK > **Run workflow**.
- Mỗi lần đổi code nên tăng `versionName` trong `app/build.gradle` để release có tên mới (cùng tên thì bản mới ghi đè bản cũ).

## Nếu vẫn không được
- Đã là bản mới nhất mà vẫn `YT_CHANGED`: YouTube mới đổi, tác giả thư viện chưa kịp vá. Chờ vài giờ đến vài ngày rồi kiểm tra lại.
- Gradle báo lỗi biên dịch ở `data/yt/` sau khi nâng: thư viện đã đổi API (thường khi nhảy bản lớn). Cần sửa code, hoặc quay lại số phiên bản cũ.
- JitPack báo không tải được bản vừa ra: lần đầu JitPack phải dựng bản đó, chờ vài phút rồi Sync lại.

## Tự cập nhật app ngay trên box (từ v1.7.0)
App tự hỏi GitHub Releases của repo (đặt trong `app/build.gradle`, dòng `UPDATE_REPO`) xem có APK mới hơn bản đang chạy không (so phiên bản app, bằng nhau thì so phiên bản NewPipe). Có bản mới thì TV hiện dòng chữ chạy nhắc, và nút **Cài đặt > Cập nhật app** hiện dấu ⚠.
- Bấm **Tải và cài**: app tải `KaraokeBox.apk`, mở trình cài đặt của Android, bạn chọn **Cài đặt** một lần. Cài xong app tự mở lại. Hàng chờ và cài đặt giữ nguyên nếu khoá ký khớp.
- Android không cho app thường cài ngầm, nên luôn còn một lần bấm xác nhận (trừ box đã root).
- Android 8+: lần đầu phải bật "Cho phép từ nguồn này" cho Karaoke Box (app tự mở sẵn màn hình đó).
- **Bản 1.7.0 đầu tiên vẫn phải cài tay** (adb hoặc tải về), vì bản 1.6.0 đang chạy chưa có tính năng này. Từ đó trở đi cập nhật ngay trên box.
- Muốn app nhận bản mới, nhớ tăng `versionName` trong `app/build.gradle` mỗi lần đổi code (release có tag mới).
