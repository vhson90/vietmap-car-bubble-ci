# 🚗 VeloHUD v3.0 (Offline 100%) — Bong Bóng Báo Tốc Độ & Camera Cho Android Box Ô Tô

Ứng dụng Android Native (`vn.velohud.auto`) tạo **Bong bóng nổi (Floating HUD Overlay)** hiển thị đè lên trên **Google Maps, YouTube, Navitel, Apple CarPlay / Android Auto** trên màn hình Android Box của xe ô tô.

---

## ✨ Tính năng nổi bật (VeloHUD v3.0)

1. **Hoạt động Offline 100% — Không cần mạng 4G, không cần tài khoản**:
   - Tích hợp sẵn cơ sở dữ liệu không gian [`velohud_offline.db`](app/src/main/assets/velohud_offline.db) (`7.97 MB`) chứa toàn bộ **69,965 điểm Biển báo tốc độ (`30`..`120 km/h`), Khu dân cư, Camera phạt nguội, Camera tốc độ, Cấm vượt, Trạm thu phí ETC & Đèn tín hiệu Hybrid** trên toàn lãnh thổ Việt Nam.
   - Đã tạo sẵn chỉ mục không gian (`idx_edog_lat_lon`), tốc độ truy vấn dưới `2ms` cho mỗi nhịp GPS.

2. **Bong bóng Siêu Gọn (Mini HUD) với Hiệu ứng Hít Mép (Edge Snap)**:
   - **Đồng hồ tốc độ hiện tại**: Thu nhận tốc độ thực tế từ chip GPS phần cứng của xe (`km/h`), hỗ trợ bù sai số đồng hồ ODO (`-5` đến `+5 km/h`) và khóa phần cứng `PARTIAL_WAKE_LOCK` + `WIFI_LOCK` chống ngủ đông GPS.
   - **Biển báo tốc độ giới hạn hiện tại**: Tự động chốt trạng thái khi xe đi qua biển báo hoặc ra/vào khu đông dân cư, xóa biển tức thì (`cleanWhenOut`) ngay khi vừa đi qua trụ biển.
   - **Nhãn báo trước thông minh (`warningAlertDistance`)**: Tự động điều chỉnh tầm quét theo vận tốc thực tế (`240m` trong phố đông, `330m` đường trục, `450m` cao tốc).
   - **Báo động đỏ + Giọng nói Tiếng Việt Phòng thu MP3 Offline (`assets/sounds/*.mp3`)**: Hỗ trợ 3 chế độ âm thanh (`Giọng nói + Ting`, `Chỉ tiếng Ting`, `Tắt âm`) và đảm bảo mỗi biển báo/camera chỉ đọc đúng 1 lần duy nhất (`markRead`).
