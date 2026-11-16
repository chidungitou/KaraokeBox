# Karaoke Box (Himedia Q10 / Android TV Box)

Phần mềm karaoke chạy hoàn toàn trong mạng LAN. Điện thoại quét mã QR trên TV rồi chọn bài bằng
trình duyệt, không cần cài app.

## Build
1. Cài **Android Studio** (Hedgehog 2023.1.1 trở lên) và **JDK 17**.
2. Mở thư mục `KaraokeBox`, đợi Gradle sync xong.
3. `Build > Build APK(s)` rồi copy `app/build/outputs/apk/debug/app-debug.apk` sang box.
4. Cài lên Q10: `adb connect <ip-box>` rồi `adb install -r app-debug.apk` (hoặc copy qua USB và cài bằng file manager).

## Lần chạy đầu tiên
1. Mở app **Karaoke Box**. Nếu chưa có thư mục nhạc, màn hình **Cài đặt** tự mở.
2. Bấm **Thêm thư mục**, duyệt tới ổ cứng (thường `/mnt/sda/sda1/...` hoặc `/mnt/sda1/...`), chọn **✔ Chọn thư mục này**.
3. App quét nhạc nền; quét xong dùng QR trên TV để mở web remote (`http://<ip>:8080`).

## Phím remote hồng ngoại
| Phím | Khi đang phát | Khi mở danh sách chờ |
|---|---|---|
| OK | Phát / Tạm dừng | Đẩy bài đang chọn lên đầu |
| Phải / Trái | Qua bài / Hát lại | |
| Lên / Xuống | Tăng / giảm âm lượng | Di chuyển chọn |
| MENU | Mở danh sách chờ | Mở cài đặt nhanh |
| Đỏ / Del | Giảm tone (−½ cung) | Xóa bài đang chọn |
| Vàng / Settings | Mở cài đặt nhanh (ngay trên màn hình đang hát) | Mở cài đặt nhanh |
| Xanh lá | Bật/tắt tiếng ca sĩ | |
| Xanh dương | Tăng tone (+½ cung) | Xóa bài đang chọn |
| Back | Nhấn 2 lần để thoát | Đóng danh sách |

## Quy ước tách lời
- File **nhiều audio track**: track 1 = nhạc + ca sĩ, track 2 = chỉ beat.
- File **1 track stereo**: mặc định kênh TRÁI = ca sĩ, kênh PHẢI = beat (đổi trong Cài đặt).
- Bài nào bị ngược quy ước: trên điện thoại **nhấn giữ nút "Có lời"** để đảo cho riêng bài đó (được ghi nhớ).

## Tìm và hát bài từ YouTube (v1.1)
Trên web remote có công tắc **Trong máy / YouTube** dưới ô tìm kiếm. Chọn **YouTube**, gõ tên bài rồi bấm Enter
(mặc định tự thêm chữ "karaoke" vào truy vấn, bỏ tick nếu không muốn). Bấm **Chọn** / **Ưu tiên** như bài thường;
bài YouTube nằm chung hàng đợi, có nhãn **YT**.

- **Cần Internet trên đầu box** (điện thoại chỉ cần cùng Wi-Fi; ảnh bìa tải từ điện thoại nên cũng cần mạng).
- Tìm kiếm và lấy link phát dùng thư viện **NewPipeExtractor** (không cần API key). YouTube hay đổi cách bảo vệ nên
  thỉnh thoảng thư viện bị hỏng: khi đó đổi `newpipeVersion=v0.26.5` trong `gradle.properties` sang bản mới nhất
  (https://github.com/TeamNewPipe/NewPipeExtractor/releases) rồi build lại. Từ v1.3.1 có màn hình hướng dẫn ngay trong app (xem bên dưới).
- Phát H.264 + AAC (giải mã phần cứng), mặc định tối đa 720p; đổi bằng `SettingsStore.youtubeMaxHeight` (360/480/720/1080).
- Bài YouTube không tách được lời (chỉ có 1 luồng tiếng), nút "Có lời" sẽ bị mờ. Nên chọn bản karaoke beat sẵn.
- Không hỗ trợ livestream. Video giới hạn độ tuổi / chặn vùng sẽ báo lỗi và tự qua bài kế.
- Khi cập nhật từ v1.0: giải nén đè lên project, **Sync Gradle**, build lại. Database không đổi nên không mất dữ liệu.

## Đổi tone và tốc độ (v1.2)
- Trên điện thoại bấm nút **Tone** ở thanh điều khiển: chỉnh tone từ **-6 đến +6 nửa cung** và tốc độ **70%–130%**, hai thứ độc lập nhau
  (đổi tone không làm bài nhanh/chậm, đổi tốc độ không làm lệch tone). Dùng được cho cả bài trong máy lẫn YouTube.
- Remote TV: phím **Đỏ** giảm tone, phím **Xanh dương** tăng tone (khi không mở danh sách chờ).
- Tone được **nhớ riêng cho từng bài** (lần sau hát lại tự áp). Tốc độ chỉ áp cho bài đang hát, sang bài mới về 100%.

## YouTube mượt hơn và tự báo lỗi (v1.2)
- **Kiểm tra ngay khi thêm bài**: video riêng tư / chặn vùng / trực tiếp sẽ được báo và gỡ khỏi hàng chờ ngay, không chờ tới lượt.
- **Tải trước bài kế tiếp**: khi bài đang hát ổn định, app lấy sẵn link và tải ~30–50 giây đầu của bài kế tiếp vào cache.
- **Cache trên box**: bài YouTube đã phát được lưu (mặc định **2 GB**, tự giảm nếu ổ còn ít chỗ trống: luôn chừa lại ~1,5 GB). Lưu ở thư mục riêng của app
  (không phải thư mục cache) nên hệ điều hành / "Xóa bộ nhớ đệm" không dọn mất. Khi đầy, xóa bài lâu chưa hát nhất nhưng **ưu tiên giữ bài Yêu thích và bài đã hát từ 3 lần**.
  Bài đã nằm **đủ** trong cache được phát thẳng, không cần Internet và không gửi request nào lên YouTube (nên YouTube chặn cũng không ảnh hưởng).
  Chỉ lưu đủ bài nào đã hát hết (hoặc gần hết) một lần. Đổi dung lượng bằng `SettingsStore.youtubeCacheMb` (0 = tắt cache); cần mở lại app mới có hiệu lực.
- **Theo dõi sức khỏe YouTube**: lỗi hệ thống liên tiếp (mất mạng, YouTube chặn, YouTube đổi dữ liệu) → điện thoại hiện dải cảnh báo
  "YouTube tạm thời không dùng được" kèm nguyên nhân, TV cũng báo một lần. Khi lỗi, box tự thử lại mỗi 45 giây và báo khi hoạt động lại;
  bài trong máy vẫn hát bình thường. Bấm **Thử lại** trên dải cảnh báo để kiểm tra ngay.
- **Nhật ký chẩn đoán**: trên điện thoại bấm **bánh răng > Xem nhật ký lỗi YouTube** (chỉ chủ phòng xem được từ v1.6.0) để xem lỗi (loại lỗi, bản NewPipeExtractor, vài dòng stack).
  Thấy nhiều dòng `code=YT_CHANGED` nghĩa là YouTube đã đổi cách trả dữ liệu → cập nhật NewPipeExtractor (xem `UPDATE_YOUTUBE.md`).

## Mất mạng giữa bài YouTube (v1.3)
- **Không còn "ăn" hết hàng chờ khi đứt mạng**: trước đây mất Internet quá ~5 giây là bài bị bỏ qua, bài kế cũng lỗi theo. Giờ box kiểm tra
  xem có ra được YouTube không: **mất mạng → giữ nguyên bài và vị trí đang hát**, cứ 3 giây thử lại, có mạng là hát tiếp đúng chỗ dở.
  TV và điện thoại có thông báo "Mất kết nối Internet..." và "Đã có mạng trở lại...".
- **Chống treo**: bài YouTube kẹt "đang tải" 15 giây không tải thêm được gì (mà ExoPlayer không báo lỗi) sẽ tự được nối lại.
- **Mất mạng quá 45 giây** mà hàng chờ có bài trong máy: hát bài trong máy trước, bài YouTube bị ngắt được đưa lại ngay sau đó (hát lại từ đầu).
  Nếu cả hàng chờ đều là YouTube thì tiếp tục chờ mạng, bấm **Qua bài** nếu muốn bỏ.
- Còn mạng nhưng luồng lỗi (link hết hạn, 403, bị cắt): vẫn nối lại tối đa 3 lần cho mỗi sự cố rồi mới qua bài. Phát ổn định 30 giây thì được tính lại từ đầu.

## Xếp hàng công bằng (v1.3)
- Bài thường (nút **Chọn**) được xếp **xen kẽ theo người thêm** (mỗi điện thoại là một người, kể cả trùng tên hiển thị): ai cũng có lượt, không ai chiếm hết hàng chờ.
  Ví dụ A thêm a1 a2 a3, rồi B thêm b1 b2, C thêm c1 → hàng chờ là `a1 b1 c1 a2 b2 a3`. Trong cùng một vòng, ai thêm trước hát trước.
- Người đang hát cũng được tính là đã có một lượt: A đang hát, B có b1 đang chờ, A thêm a2 → a2 đứng sau b1.
- **Ưu tiên** vẫn chen lên đầu, kéo thả / "Lên đầu" vẫn đổi thứ tự thủ công như cũ.
- Điện thoại báo vị trí bài vừa thêm ("Đã thêm vào danh sách chờ (bài thứ 4)") vì bài có thể nằm giữa hàng.
- Tắt/bật ở **Cài đặt** trên TV: "Xếp hàng công bằng" (mặc định bật). Tắt thì bài mới xuống cuối hàng như v1.2.
- Hàng chờ khôi phục sau khi tắt/mở máy dùng tên hiển thị để phân biệt người (mã điện thoại không được lưu).

## Cập nhật YouTube ngay trong Cài đặt (v1.3.1)
- Vào **Cài đặt > nút "YouTube: v..."** (bằng remote hồng ngoại). Màn hình cho biết: bản NewPipeExtractor đang dùng, **bản mới nhất trên GitHub**,
  tình trạng YouTube hiện tại, và **hướng dẫn nâng cấp từng bước** có sẵn số phiên bản cần gõ.
- Nút trong Cài đặt tự hiện **⚠ có bản mới** (app kiểm tra nền tối đa 12 giờ/lần, cần Internet). Khi YouTube lỗi, box cũng tự xem có bản mới không (tối đa 1 lần/giờ)
  và cảnh báo trên điện thoại/TV chỉ thẳng tới màn hình này.
- Số phiên bản giờ nằm ở **`gradle.properties`** (dòng `newpipeVersion=...`), không cần đụng `app/build.gradle`.
  Có thể ghi đè khi build bằng dòng lệnh: `gradlew assembleDebug -PnewpipeVersion=v0.27.0`.
- Thư viện vẫn được biên dịch cùng app nên nâng cấp vẫn phải build lại APK và cài đè; app chỉ nhắc và hướng dẫn, không tự tải bản mới.

## Chỉnh cài đặt ngay khi đang hát (v1.4)
Trước đây cài đặt chỉ mở được ở màn hình ngoài (Activity riêng, video tạm ẩn). Giờ có hai nơi chỉnh, **dùng chung một nguồn** nên đổi ở đâu thì nơi kia và bài đang hát cập nhật theo ngay.

- **Trên điện thoại**: bấm biểu tượng **bánh răng** ở đầu trang. Có 4 mục: **kênh tiếng ca sĩ** (TRÁI/PHẢI, cho file 1 track stereo, đổi là nghe thấy ngay),
  **xếp hàng công bằng**, **chất lượng YouTube** (360/480/720/1080p, áp dụng từ bài YouTube kế tiếp) và **giao diện màn hình chờ**.
  Nhiều điện thoại cùng chỉnh thì người đổi sau thắng, mọi máy tự cập nhật; TV hiện một dòng chữ chạy cho biết ai vừa đổi gì.
- **Trên TV**: phím **Vàng / Settings** (hoặc MENU khi đang mở danh sách chờ) mở **bảng cài đặt nhanh** nổi ngay trên video, không rời màn hình hát.
  Lên/Xuống chọn dòng, Trái/Phải/OK đổi giá trị, BACK hoặc Vàng để đóng. Dòng cuối mở **Cài đặt đầy đủ** (thư mục nhạc, quét lại, cập nhật YouTube...).
- **Cài đặt quản trị chỉ chỉnh trên TV** (thư mục nhạc, quét lại, đảo "Ca sĩ - Tên bài", tự mở khi khởi động, dung lượng cache, cổng): cố ý không mở qua điện thoại
  vì ai cùng Wi-Fi cũng vào được web remote.
- API: `GET /api/settings` và `POST /api/settings` (body JSON một phần: `leftIsVocal`, `fairQueue`, `youtubeMaxHeight`, `stageTheme`, `guestCanSkip`, `audioFx`, `audioFxLevel`); `POST /api/settings/reset` (chủ phòng) đặt lại các mục này về mặc định; WebSocket phát sự kiện `settings.changed`.
- Nút **bánh răng trên màn hình chờ** vẫn mở thẳng Cài đặt đầy đủ như cũ.

## Hiệu ứng âm thanh phòng thu (v1.5)
Nhạc nền được đưa qua một chuỗi xử lý như tại phòng thu: **EQ** (lọc rung trầm, bass, bớt đục, rõ lời, sáng) → **nén động** (tiếng đều, tự bù âm lượng)
→ **vang** (thuật toán Freeverb, mỗi kênh một bộ) → **mở rộng âm trường stereo** → **limiter** chống vỡ tiếng. Chạy hoàn toàn bằng phần mềm trong app
(không phụ thuộc chip âm thanh của box), dùng được cho cả bài trong máy lẫn YouTube, chạy trước bộ đổi tone/tốc độ.

| Hiệu ứng | Cảm giác |
|---|---|
| **Phòng thu** (mặc định) | Sạch, đầy, sáng; vang ngắn; giọng hát rõ |
| **Hội trường / Nhà hát** | Vang dài, không gian rộng; hợp nhạc trữ tình, bolero |
| **Sân khấu** | Bass dày, sáng, nén chắc, âm trường rộng |
| **Bass ấm** | Nhấn bass; hợp nhạc sôi động, loa nhỏ |
| **Rõ lời** | Bớt đục vùng giữa, nhấn dải giọng hát |
| **Tắt** | Âm gốc, không xử lý, không tốn CPU |

- Chọn ở **điện thoại** (bánh răng > *Hiệu ứng âm thanh phòng thu*, có thanh **Cường độ** 0–100%) hoặc **TV** (nút Vàng > hai dòng đầu: Trái/Phải đổi hiệu ứng và cường độ, mỗi bước 10%).
  Đổi giữa chừng bài là nghe thấy ngay, không bị ngắt tiếng. Cường độ mặc định 70%; kéo về 0 = tắt.
- Lưu ý: bản này xử lý **âm nhạc phát ra từ box**. Micro hát thường cắm vào amply/mixer riêng nên không đi qua box; muốn vang cho micro thì dùng vang của amply.
- Hiệu ứng làm bài to hơn khoảng 1–2 dB (đã có limiter). Nếu loa/amply bị vỡ tiếng, giảm cường độ hoặc giảm âm lượng.
- Chip yếu: bộ xử lý rất nhẹ (khoảng vài % CPU trên lõi đơn), nhưng nếu thấy giật tiếng thì chọn **Tắt**.
- API: `POST /api/settings` nhận thêm `audioFx` (`OFF|STUDIO|HALL|LIVE|BASS|VOCAL`) và `audioFxLevel` (0–100); `GET /api/settings` trả thêm `audioFxModes`.
- Mã nguồn: `player/audio/StudioDsp.kt` (thuật toán, không phụ thuộc Android), `StudioAudioProcessor.kt` (gắn vào ExoPlayer), `StudioPreset.kt` (thông số từng hiệu ứng, chỉnh ở đây nếu muốn đổi "gu" âm thanh).

## Ổn định kết nối & hiển thị tiêu đề (v1.5.1)
- **Tiêu đề bài đang hát** nằm riêng một dòng full-width, tối đa 2 dòng (chạm vào để xem đủ); tên ca sĩ, ♡, ⚙ và tên thiết bị ở dòng dưới.
  Tên video YouTube được làm gọn: bỏ hashtag (`#LIVETAISINH`...) và dấu `|` `-` thừa ở đầu/cuối (cả trên điện thoại lẫn TV, vì server làm sạch lúc thêm bài/yêu thích).
- **Tắt màn hình / để yên điện thoại**: Android đóng băng trang web nên socket có thể đứt. Giờ web nối lại **ngay** khi mở lại màn hình (`visibilitychange`, `pageshow`, `online`...),
  tự phát hiện socket "zombie" bằng ping/pong (4 giây không phản hồi là nối lại), và **chỉ hiện "Mất kết nối" sau ~4 giây mất thật sự**.
  Trong lúc chưa nối lại, web lấy trạng thái bằng HTTP (`/api/state`, 3 giây/lần) nên bài đang hát và hàng chờ luôn đúng.
- **Hiện diện 30 phút**: server tính điện thoại là "còn trong phòng" nếu trong 30 phút có bất kỳ hoạt động nào (ping, thao tác), không phụ thuộc socket đang mở hay đứt.
  TV không nhấp nháy giữa màn hình chờ/QR khi ai đó tắt màn hình; lời chào khi vào phòng cũng chỉ hiện lại sau 30 phút. Socket im lặng quá 120 giây (trước đây 40 giây) mới bị đóng.

## Gỡ chức năng micro, Cài đặt trên điện thoại gọn hơn (v1.8.0, nút cài bản mới từ v1.9.0)
- **Đã gỡ hoàn toàn micro cắm vào box** (thu âm, vang micro, xin quyền `RECORD_AUDIO`): micro hát đã có đầu riêng nên box không cần thu âm.
  App không còn xin quyền micro. API `POST /api/settings` không còn nhận `micEnabled` / `micVolume` / `micEcho`. Cài đặt micro cũ (nếu có) nằm im trong máy, không ảnh hưởng gì.
- **Cài đặt trên điện thoại (bánh răng) có thêm mục "Ứng dụng"** ở cuối:
  - Hiện **phiên bản đang chạy** (kèm bản NewPipe).
  - Nút **Kiểm tra cập nhật**: hỏi GitHub Releases và báo có bản mới hay chưa (giãn tối thiểu 15 giây giữa hai lần để không bị GitHub chặn).
    API: `POST /api/app/update/check`.
  - Nút **Tải và cài bản mới** (v1.9.0, hiện khi có bản mới, chỉ chủ phòng, bấm hai lần để xác nhận): box tự tải APK từ GitHub (điện thoại thấy % tiến độ),
    tạm dừng bài đang hát rồi mở trình cài đặt của Android **trên TV**; bạn chỉ còn bấm **Cài đặt** một lần ở TV, cài xong box tự mở lại app (điện thoại tải lại trang).
    Box Android 8+ lần đầu sẽ hiện màn hình xin quyền "Cho phép từ nguồn này": bật trên TV rồi bấm lại nút. Nếu bản mới ký bằng khoá khác thì dừng và báo lý do (không tải vô ích).
    Bản 1.8.0 trở về trước chưa có nút này nên lần nâng lên 1.9.0 vẫn làm trên TV (Cài đặt > Cập nhật app) hoặc bằng adb. API: `GET /api/app/update/status`, `POST /api/app/update/install`; WebSocket phát `app.update`.
  - Nút **Đặt lại mặc định** (chủ phòng, bấm hai lần để xác nhận): kênh ca sĩ TRÁI, xếp hàng công bằng BẬT, khách qua bài BẬT, YouTube 720p, giao diện chờ TỰ ĐỘNG,
    hiệu ứng Phòng thu, cường độ 70%. Không đụng tới thư mục nhạc, hàng chờ, yêu thích, tone từng bài. API: `POST /api/settings/reset`.

## Ổn định máy chủ (v1.6.0)
- Lệnh điều khiển chuyển sang luồng Main có **thời hạn 5 giây**: nếu Main đang kẹt, điện thoại nhận lỗi 503 "Box đang bận" thay vì treo luôn luồng của máy chủ.
- Body request tối đa **64 KB** (lớn hơn trả lỗi 413).
- `/api/youtube/diag` chỉ **chủ phòng** xem được (có nút trong Cài đặt trên điện thoại).
- **Thư mục nhạc và quét lại** (`/api/library/scan`, `/api/library/folder`) chỉ nhận request từ chính box (TV), đúng với mô tả "cài đặt quản trị chỉ chỉnh trên TV". Chủ phòng trên điện thoại không còn gọi được; web remote vốn không dùng các API này.
- Mã lỗi HTTP trả đúng: 403 (không đủ quyền), 413, 503 (trước đây 403 bị gửi thành 400; nội dung JSON không đổi).

## Tự build APK (v1.6.0)
Có workflow GitHub Actions (`.github/workflows/build-apk.yml`): mỗi ngày kiểm tra NewPipeExtractor, có bản mới thì tự build và đăng APK lên *Releases*.
Cài lên box bằng cách tải `https://github.com/<tài-khoản>/<repo>/releases/latest/download/KaraokeBox.apk`. Cách thiết lập một lần (đặc biệt là **khoá ký** để cài đè không mất dữ liệu): xem `UPDATE_YOUTUBE.md`.

## Kiểm thử
`app/src/test/.../UpdateVersionTest.kt` kiểm tra logic so sánh phiên bản khi tự cập nhật. Chạy: Android Studio (chuột phải file > Run) hoặc `gradle testDebugUnitTest`.

## Cấu trúc
- `server/`: NanoHTTPD (REST + WebSocket cùng cổng 8080, tự dò 8080–8090 nếu bận)
- `player/`: ExoPlayer, `ChannelSelectAudioProcessor` (tách L/R), `StudioAudioProcessor` (hiệu ứng phòng thu), `PlaybackController` (nguồn trạng thái duy nhất)
- `data/`: Room, quét thư viện tăng dần, parse tên file, tìm kiếm không dấu; `data/yt/`: tìm kiếm + giải link YouTube
- `ui/`: màn hình TV, QR, chữ chạy, danh sách chờ, bảng cài đặt nhanh (`SettingsPanelView`), cài đặt đầy đủ
- `assets/web/`: giao diện điện thoại

## Lưu ý / xử lý sự cố
- Không phát được DTS/AC3 trong MKV/VOB: tùy firmware, cần build thêm extension FFmpeg của Media3.
- Không đọc được ổ cứng: kiểm tra đường dẫn mount bằng file manager, App cần quyền đọc bộ nhớ (target SDK 28 nên tự cấp trên Android 5-7;
  nếu box chạy Android 6+ mà thấy rỗng, vào Settings > Apps > Karaoke Box > Permissions bật Storage).
- Điện thoại không vào được web: đảm bảo cùng Wi-Fi/LAN với box, và router không bật "AP isolation".
- Âm lượng không đổi: một số box xuất HDMI/optical cố định âm lượng, hãy chỉnh trên amply/TV.
