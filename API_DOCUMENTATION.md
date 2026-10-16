# Tài liệu API Involens Backend

Tài liệu đối chiếu mã nguồn ngày 05/10/2026; bổ sung quản lý và xóa mềm tài khoản ngày 09/10/2026 tại mục 3.5–3.8. Không bao gồm Health, OpenAPI và Swagger UI.

## 1. Quy ước sử dụng

- Base URL: `http://localhost:8080`.
- Tất cả API ngoài login cần header `Authorization: Bearer <ACCESS_TOKEN>`.
- Thay `<ACCESS_TOKEN>` bằng `data.accessToken` nhận từ login. API tạo tài khoản cần token MANAGER.
- Email, mật khẩu, ID, số liệu và thời gian trong ví dụ là dữ liệu minh họa; thay bằng dữ liệu có thật trong môi trường chạy. Token mẫu không phải token hợp lệ.
- cURL bên dưới dùng cú pháp Bash/Git Bash với ký tự `\` xuống dòng. Có thể ghép thành một dòng để chạy; không dán nguyên cú pháp xuống dòng này vào PowerShell.
- JSON request dùng tên trường camelCase; nhiều response và query dùng snake_case. Giữ đúng tên trường được ghi cho từng API.
- Request GET không có body. Response thành công của các API bên dưới là HTTP **200**, kể cả tạo tài khoản và upload.
- `status` ở lớp ngoài là mã ứng dụng (`0`: thành công, `1`: lỗi), không phải HTTP status. `data.status` nếu có là trạng thái đối tượng nghiệp vụ.
- Response mẫu trình bày các trường nullable bằng `null` theo DTO/Jackson hiện tại. Không dùng một response lỗi chung cho lỗi tại security filter hoặc lỗi chưa được handler tùy chỉnh xử lý.
- Các ví dụ được đối chiếu tĩnh với mã nguồn; chưa gọi API trên server thật.

Luồng sử dụng: login → lấy token → upload → lấy danh sách/chi tiết → đọc kết quả trích xuất nếu đã tồn tại → PATCH sửa/xác nhận hóa đơn → xem preview export.

**Giới hạn hiện tại:** `ExtractionMessageService` còn TODO cho nội dung message gửi AI và lưu kết quả RabbitMQ. Upload chưa bảo đảm tự sinh extraction result/invoice; các ví dụ đọc/sửa kết quả cần dữ liệu đã tồn tại trong database. Preview export chỉ trả số lượng, không tạo job hay trả file tải xuống.

## 2. Danh sách đầy đủ API

| STT | Method | URL đầy đủ | Chức năng | Quyền |
|---|---|---|---|---|
| 1 | POST | `http://localhost:8080/api/v1/auth/login` | Đăng nhập | Công khai |
| 2 | POST | `http://localhost:8080/api/v1/auth/create-accounts` | Tạo tài khoản | MANAGER |
| 3 | GET | `http://localhost:8080/api/v1/auth/me` | Thông tin người dùng hiện tại | JWT |
| 4 | POST | `http://localhost:8080/api/v1/auth/logout` | Xác nhận đăng xuất | JWT |
| 5 | POST | `http://localhost:8080/api/v1/documents/upload` | Upload một hoặc nhiều tài liệu | JWT |
| 6 | GET | `http://localhost:8080/api/v1/documents/summary` | Thống kê tài liệu | JWT |
| 7 | GET | `http://localhost:8080/api/v1/documents` | Danh sách tài liệu | JWT |
| 8 | GET | `http://localhost:8080/api/v1/documents/{documentId}` | Chi tiết tài liệu | JWT |
| 9 | GET | `http://localhost:8080/api/v1/documents/{documentId}/extraction-result` | Kết quả trích xuất | JWT |
| 10 | PATCH | `http://localhost:8080/api/v1/documents/{documentId}/extraction-result` | Sửa/xác nhận hóa đơn | JWT |
| 11 | GET | `http://localhost:8080/api/v1/review-tasks` | Danh sách tác vụ review | JWT |
| 12 | POST | `http://localhost:8080/api/v1/export-jobs/preview` | Đếm hóa đơn dự kiến export | JWT |
| 13 | DELETE | `http://localhost:8080/api/v1/auth/accounts/{id}` | Xóa mềm tài khoản | MANAGER |
| 14 | PATCH | `http://localhost:8080/api/v1/auth/accounts/{id}` | Cập nhật quyền/trạng thái tài khoản | MANAGER |
| 15 | GET | `http://localhost:8080/api/v1/auth/accounts` | Danh sách tài khoản hoạt động | MANAGER |
| 16 | GET | `http://localhost:8080/api/v1/auth/accounts/{id}` | Chi tiết tài khoản hoạt động | MANAGER |

Trong các API tài liệu, review và preview: EMPLOYEE chỉ thấy tài liệu của mình; MANAGER thấy tài liệu của tất cả người dùng. Tài liệu phải có trạng thái `ACTIVE`.

## 3. Authentication

### 3.1. Đăng nhập

**POST** `http://localhost:8080/api/v1/auth/login`

Header: `Content-Type: application/json`. Không cần token.

| Trường body | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| email | string | Có | Không rỗng, đúng định dạng email |
| password | string | Có | Không rỗng |

Request mẫu và cURL:

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{"email":"manager@example.com","password":"Password!123"}'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "accessToken": "<JWT_ACCESS_TOKEN>",
    "expiresIn": 3600,
    "user": {"id": 1, "email": "manager@example.com", "fullName": "Nguyen Van A", "role": "MANAGER"}
  }
}
```

`expiresIn` tính bằng giây, lấy từ cấu hình `app.jwt.expiration-seconds` (hiện tại 3600). Email được trim và tìm không phân biệt hoa/thường. Sai email/mật khẩu: 401 `AUTH_INVALID_CREDENTIALS`; tài khoản `INACTIVE`: 401 `AUTH_INVALID_CREDENTIALS` với message `Account not found`, không kiểm tra mật khẩu hoặc cấp JWT; validation: 400.

Response đăng nhập tài khoản đã xóa mềm — HTTP 401:

```json
{"status": 1, "error": {"code": "AUTH_INVALID_CREDENTIALS", "message": "Account not found"}, "data": null}
```

### 3.2. Tạo tài khoản

**POST** `http://localhost:8080/api/v1/auth/create-accounts`

Headers: `Content-Type: application/json`, `Authorization: Bearer <ACCESS_TOKEN>` của MANAGER.

| Trường body | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| email | string | Có | Không rỗng, email hợp lệ, không trùng email đã đăng ký (không phân biệt hoa/thường) |
| password | string | Có | Ít nhất 8 ký tự; có chữ hoa và ký tự đặc biệt; không có khoảng trắng |
| fullName | string | Có | Không rỗng |
| role | string enum | Có | `EMPLOYEE` hoặc `MANAGER` |

Danh sách ký tự đặc biệt được regex chấp nhận: `` !@#$%^&*()_+-=[]{};':"\|,.<>/? ``. Không có yêu cầu riêng về chữ số trong validation hiện tại.

Request mẫu và cURL:

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/create-accounts' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{"email":"employee@example.com","password":"Password!123","fullName":"Nguyen Van B","role":"EMPLOYEE"}'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {"id": 2, "email": "employee@example.com", "fullName": "Nguyen Van B", "role": "EMPLOYEE"}
}
```

Tài khoản mới có trạng thái ACTIVE; email và fullName được trim, mật khẩu được mã hóa BCrypt. EMPLOYEE gọi API: 403 `ACCESS_DENIED`; email trùng: 409 `EMAIL_ALREADY_EXISTS`; validation: 400; JWT thiếu/sai/hết hạn: 401.

### 3.3. Thông tin người dùng hiện tại

**GET** `http://localhost:8080/api/v1/auth/me`

Không có query hoặc body.

```bash
curl --request GET 'http://localhost:8080/api/v1/auth/me' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {"id": 1, "email": "manager@example.com", "fullName": "Nguyen Van A", "role": "MANAGER"}
}
```

JWT thiếu/sai/hết hạn, đã bị thu hồi hoặc thuộc tài khoản `INACTIVE`: 401 tại security filter. Nếu tài khoản chuyển sang `INACTIVE` sau khi JWT được kiểm tra, service trả 401 `AUTH_INVALID_CREDENTIALS` với message `Account not found`.

### 3.4. Đăng xuất

**POST** `http://localhost:8080/api/v1/auth/logout`

Không có query hoặc body.

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/logout' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{"status": 0, "error": {"code": "00", "message": "Logout successfully"}, "data": null}
```

Logout là stateless: client phải xóa access token. Server không thu hồi hoặc blacklist JWT trong API này. JWT thiếu/sai/hết hạn: 401.

### 3.5. Xóa mềm tài khoản

**DELETE** `http://localhost:8080/api/v1/auth/accounts/{id}`

Header: `Authorization: Bearer <ACCESS_TOKEN>` của MANAGER. `id` là ID tài khoản cần xóa. Không có query hoặc body.

```bash
curl --request DELETE 'http://localhost:8080/api/v1/auth/accounts/2' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>'
```

Response — HTTP 200:

```json
{"status": 0, "error": null, "data": null}
```

API chuyển `users.status` sang `INACTIVE`, tăng `token_version` để thu hồi JWT và giữ bản ghi cùng dữ liệu liên quan. Xóa lại tài khoản đã `INACTIVE` vẫn trả HTTP 200, không tăng `token_version` thêm. Không được xóa manager hoạt động cuối cùng.

| HTTP | error.code | Trường hợp |
|---|---|---|
| 400 | VALIDATION_ERROR | Xóa manager hoạt động cuối cùng: `Cannot deactivate or demote the last active manager` |
| 400 | VALIDATION_ERROR | ID sai kiểu: `Invalid parameter: id` |
| 401 | Security filter | Thiếu JWT, JWT sai/hết hạn/bị thu hồi, hoặc account người gọi đã `INACTIVE` |
| 403 | ACCESS_DENIED | Người gọi không phải MANAGER: `Only managers can manage accounts` |
| 404 | ACCOUNT_NOT_FOUND | ID không tồn tại: `Account not found` |

Các lỗi business có cấu trúc `{"status":1,"error":{"code":"...","message":"..."},"data":null}`. Lỗi 401 tại security filter không cam kết cấu trúc `BaseResponse`.

### 3.6. Cập nhật quyền và trạng thái tài khoản

**PATCH** `http://localhost:8080/api/v1/auth/accounts/{id}`

Headers: `Authorization: Bearer <ACCESS_TOKEN>` của MANAGER, `Content-Type: application/json`.

| Trường body | Kiểu | Bắt buộc | Giá trị |
|---|---|---|---|
| role | string enum | Không | `EMPLOYEE`, `MANAGER` |
| status | string enum | Không | `ACTIVE`, `INACTIVE` |

Phải có ít nhất một trường. Trường bỏ qua giữ nguyên; không chấp nhận `null` hoặc `LOCKED`. Không được hạ quyền/chuyển `INACTIVE` cho manager hoạt động cuối cùng. Khi quyền hoặc trạng thái thay đổi, JWT hiện có bị thu hồi; gửi cùng giá trị không thu hồi thêm.

Request ví dụ:

```json
{"role": "EMPLOYEE", "status": "INACTIVE"}
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "id": 2,
    "email": "employee@example.com",
    "fullName": "Nguyen Van B",
    "role": "EMPLOYEE",
    "status": "INACTIVE",
    "createdAt": "2026-10-01T09:00:00",
    "updatedAt": "2026-10-09T14:30:00"
  }
}
```

Để kích hoạt lại tài khoản đã xóa mềm theo ID, gửi `{"status":"ACTIVE"}` tới cùng URL. JWT cũ vẫn bị thu hồi; tài khoản phải đăng nhập lại.

Lỗi: 400 `VALIDATION_ERROR` cho body rỗng, enum sai, `null` hoặc vi phạm manager cuối cùng; 403 `ACCESS_DENIED` nếu không phải MANAGER; 404 `ACCOUNT_NOT_FOUND` nếu ID không tồn tại; 401 nếu JWT không hợp lệ.

### 3.7. Danh sách tài khoản đang hoạt động

**GET** `http://localhost:8080/api/v1/auth/accounts?page=1&page_size=8`

Header: `Authorization: Bearer <ACCESS_TOKEN>` của MANAGER. Không có body. Query mặc định `page=1`, `page_size=8`; `page >= 1`, `1 <= page_size <= 100`.

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [{
      "id": 2,
      "email": "employee@example.com",
      "fullName": "Nguyen Van B",
      "role": "EMPLOYEE",
      "status": "ACTIVE",
      "createdAt": "2026-10-01T09:00:00",
      "updatedAt": "2026-10-09T14:30:00"
    }],
    "total": 1,
    "page": 1,
    "page_size": 8
  }
}
```

Chỉ trả `ACTIVE`, sắp xếp ID tăng dần. `total` chỉ đếm tài khoản `ACTIVE`; trang rỗng có `items: []`. Lỗi: 400 cho phân trang sai, 403 cho người gọi không phải MANAGER, 401 cho JWT không hợp lệ.

### 3.8. Chi tiết tài khoản đang hoạt động

**GET** `http://localhost:8080/api/v1/auth/accounts/{id}`

Header: `Authorization: Bearer <ACCESS_TOKEN>` của MANAGER. Không có query hoặc body. Response HTTP 200 có cùng cấu trúc thông tin tài khoản như PATCH, với `data.status` là `ACTIVE`.

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "id": 2,
    "email": "employee@example.com",
    "fullName": "Nguyen Van B",
    "role": "EMPLOYEE",
    "status": "ACTIVE",
    "createdAt": "2026-10-01T09:00:00",
    "updatedAt": "2026-10-09T14:30:00"
  }
}
```

Tài khoản không tồn tại hoặc `INACTIVE` đều trả HTTP 404:

```json
{"status": 1, "error": {"code": "ACCOUNT_NOT_FOUND", "message": "Account not found"}, "data": null}
```

Lỗi khác: 400 cho ID sai kiểu, 403 cho người gọi không phải MANAGER, 401 cho JWT không hợp lệ.

## 4. Documents

### 4.1. Upload tài liệu

**POST** `http://localhost:8080/api/v1/documents/upload`

Body: `multipart/form-data`, một hoặc nhiều part cùng tên **`files`**. cURL tự đặt Content-Type và boundary khi dùng `--form`.

| Part | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| files | file, lặp lại cho nhiều file | Có | Không rỗng; tối đa 10 × 1024 × 1024 bytes/file; phần mở rộng JPG/JPEG/PNG/PDF và MIME tương ứng |

MIME hỗ trợ: JPG/JPEG → `image/jpeg`; PNG → `image/png`; PDF → `application/pdf`. Thay đường dẫn bằng file có thật.

```bash
curl --request POST 'http://localhost:8080/api/v1/documents/upload' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json' \
  --form 'files=@/path/to/invoice.pdf;type=application/pdf' \
  --form 'files=@/path/to/invoice.png;type=image/png'
```

Response mẫu — HTTP 200, cả hai file hợp lệ:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [
      {"id": "11111111-1111-4111-8111-111111111111", "original_filename": "invoice.pdf", "mime_type": "application/pdf", "file_size": 102400, "upload_status": "UPLOADED", "current_state": "UPLOADED", "uploaded_at": "2026-10-05T02:00:00Z"},
      {"id": "22222222-2222-4222-8222-222222222222", "original_filename": "invoice.png", "mime_type": "image/png", "file_size": 204800, "upload_status": "UPLOADED", "current_state": "UPLOADED", "uploaded_at": "2026-10-05T02:00:00Z"}
    ]
  }
}
```

Response mẫu — HTTP 200 nhưng một file trùng nội dung:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [
      {"id": "33333333-3333-4333-8333-333333333333", "original_filename": "duplicate.pdf", "mime_type": "application/pdf", "file_size": 102400, "upload_status": "FAILED", "current_state": "UPLOADED_FAILED", "upload_error_code": "DUPLICATE_FILE", "upload_error_message": "Tệp có nội dung trùng với tài liệu đã được tải lên"}
    ]
  }
}
```

Các trường null của upload item được bỏ khỏi JSON. File lỗi validation vẫn được lưu thành bản ghi lỗi; batch có thể gồm cả file thành công và thất bại.

| upload_error_code | Điều kiện |
|---|---|
| EMPTY_FILE | File rỗng |
| FILE_TOO_LARGE | File lớn hơn 10 MiB |
| UNSUPPORTED_FORMAT | Phần mở rộng/MIME không được hỗ trợ |
| DUPLICATE_FILE | SHA-256 trùng trong batch hoặc trùng tài liệu ACTIVE, UPLOADED đã lưu; kiểm tra toàn hệ thống, không chỉ tài khoản hiện tại |

File thành công được lưu MinIO. Sau commit, service gọi bước gửi yêu cầu extraction, hiện còn TODO như ghi tại mục 1. Lỗi storage/persistence trong xử lý batch: HTTP 500 `DOCUMENT_UPLOAD_FAILED`, rollback batch và thực hiện dọn object đã upload. Multipart không hợp lệ/thiếu `files`: lỗi request; JWT thiếu/sai/hết hạn: 401.

### 4.2. Thống kê tài liệu

**GET** `http://localhost:8080/api/v1/documents/summary`

Không có query hoặc body.

```bash
curl --request GET 'http://localhost:8080/api/v1/documents/summary' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "total": 10,
    "processing": 2,
    "review_required": 3,
    "success": 2,
    "rejected": 1,
    "failed": 1,
    "daily_uploads": [
      {"date": "2026-09-29", "count": 1},
      {"date": "2026-09-30", "count": 1},
      {"date": "2026-10-01", "count": 2},
      {"date": "2026-10-02", "count": 1},
      {"date": "2026-10-03", "count": 0},
      {"date": "2026-10-04", "count": 2},
      {"date": "2026-10-05", "count": 3}
    ]
  }
}
```

`total`: tất cả tài liệu ACTIVE trong phạm vi quyền; các bộ đếm là tổng mọi thời điểm. `processing` chỉ tính PROCESSING; `review_required`: REVIEW_REQUIRED; `success`: EXTRACT_SUCCESS; `rejected`: UPLOADED_FAILED; `failed`: EXTRACT_FAILED. UPLOADED chỉ đóng góp vào `total`, nên tổng các nhóm hiển thị có thể nhỏ hơn `total`.

`daily_uploads`: ngày hiện tại và 6 ngày trước theo Asia/Ho_Chi_Minh, cũ trước mới sau. Không có dữ liệu vẫn trả đủ 7 ngày và số đếm 0. JWT thiếu/sai/hết hạn: 401.

### 4.3. Danh sách tài liệu

**GET** `http://localhost:8080/api/v1/documents`

Không có body. Tất cả query parameters đều tùy chọn:

| Query | Kiểu | Mặc định/ý nghĩa |
|---|---|---|
| current_state | enum | `UPLOADED`, `PROCESSING`, `EXTRACT_SUCCESS`, `REVIEW_REQUIRED`, `EXTRACT_FAILED`, `UPLOADED_FAILED` |
| filename | string | Tìm chứa tên file, không phân biệt hoa/thường |
| uploaded_by | long | MANAGER lọc theo ID uploader; EMPLOYEE bị giới hạn tài liệu của mình và tham số này bị bỏ qua |
| date_from | date | `YYYY-MM-DD`, từ đầu ngày upload theo giờ Việt Nam |
| date_to | date | `YYYY-MM-DD`, gồm toàn bộ ngày cuối |
| q | string | Tìm chứa tên file như filename |
| page | integer | 1, tối thiểu 1 |
| page_size | integer | 8, từ 1 đến 100 |

Các bộ lọc kết hợp bằng AND; `filename`/`q` blank bị bỏ qua. Ngày bắt đầu không được sau ngày kết thúc. Sắp xếp `uploaded_at` giảm dần, rồi `id` giảm dần.

URL request mẫu đầy đủ:

```text
http://localhost:8080/api/v1/documents?current_state=REVIEW_REQUIRED&filename=invoice&uploaded_by=2&date_from=2026-10-01&date_to=2026-10-05&q=pdf&page=1&page_size=8
```

```bash
curl --request GET 'http://localhost:8080/api/v1/documents?current_state=REVIEW_REQUIRED&filename=invoice&uploaded_by=2&date_from=2026-10-01&date_to=2026-10-05&q=pdf&page=1&page_size=8' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [
      {"id": "11111111-1111-4111-8111-111111111111", "original_filename": "invoice.pdf", "uploaded_by_name": "Nguyen Van B", "uploaded_at": "2026-10-05T02:00:00Z", "current_state": "REVIEW_REQUIRED"}
    ],
    "total": 1,
    "page": 1,
    "page_size": 8
  }
}
```

Không có kết quả: `items: []`, `total: 0`. Sai enum/ngày/phân trang hoặc khoảng ngày ngược: HTTP 400. JWT thiếu/sai/hết hạn: 401.

### 4.4. Chi tiết tài liệu

**GET** `http://localhost:8080/api/v1/documents/{documentId}`

Path `documentId` bắt buộc, kiểu UUID. Không có query/body.

```bash
curl --request GET 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "id": "11111111-1111-4111-8111-111111111111",
    "original_filename": "invoice.pdf",
    "mime_type": "application/pdf",
    "file_size": 102400,
    "status": "ACTIVE",
    "upload_status": "UPLOADED",
    "current_state": "REVIEW_REQUIRED",
    "uploaded_by": {"id": 2, "full_name": "Nguyen Van B"},
    "uploaded_at": "2026-10-05T02:00:00Z"
  }
}
```

Response không cung cấp URL tải file. UUID sai: 400 `VALIDATION_ERROR`; không tồn tại, INACTIVE hoặc ngoài quyền EMPLOYEE: 404 `DOCUMENT_NOT_FOUND`; JWT thiếu/sai/hết hạn: 401.

### 4.5. Kết quả trích xuất

**GET** `http://localhost:8080/api/v1/documents/{documentId}/extraction-result`

Path `documentId` bắt buộc, UUID của tài liệu. Không có query/body.

```bash
curl --request GET 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111/extraction-result' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "id": "44444444-4444-4444-8444-444444444444",
    "document_id": "11111111-1111-4111-8111-111111111111",
    "ai_status": "REVIEW_REQUIRED",
    "retry_count": 0,
    "human_review_required": true,
    "overall_confidence": 0.85,
    "raw_json": {},
    "error_code": null,
    "error_message": null,
    "processing_started_at": "2026-10-05T02:01:00Z",
    "processing_finished_at": "2026-10-05T02:02:00Z"
  }
}
```

`ai_status`: REVIEW_REQUIRED, PROCESSING, SUCCESS hoặc FAILED. `raw_json` là map JSON lưu trong database, có thể null; `{}` chỉ minh họa, chưa có schema message AI được hoàn thiện trong mã nguồn.

AI thất bại vẫn trả HTTP 200, `status: 0`, `data.ai_status: "FAILED"` và các trường `data.error_code`/`data.error_message` theo dữ liệu đã lưu. Đây không phải lỗi HTTP của thao tác đọc.

UUID sai: 400; không thấy tài liệu: 404 `DOCUMENT_NOT_FOUND`; chưa có extraction result: 404 `EXTRACTION_RESULT_NOT_FOUND`; JWT thiếu/sai/hết hạn: 401.

### 4.6. Sửa hoặc xác nhận hóa đơn trích xuất

**PATCH** `http://localhost:8080/api/v1/documents/{documentId}/extraction-result`

Path `documentId`: UUID bắt buộc. Headers: Bearer token và `Content-Type: application/json`.

| Trường body | Kiểu | Quy tắc |
|---|---|---|
| sellerName | string | Không blank/null nếu gửi; bắt buộc khi invoice chưa tồn tại |
| sellerAddress | string hoặc null | Tùy chọn; null để xóa giá trị |
| invoiceNumber | string | Không blank/null nếu gửi; bắt buộc khi invoice chưa tồn tại |
| taxCode | string hoặc null | Tùy chọn; null để xóa giá trị |
| invoiceDate | string date | `YYYY-MM-DD`, không được sau ngày hiện tại tại Việt Nam; bắt buộc khi invoice chưa tồn tại |
| totalAmount | JSON number | Lớn hơn 0, không nhận chuỗi số/null; bắt buộc khi invoice chưa tồn tại |
| currency | string | Không blank/null nếu gửi; bắt buộc khi invoice chưa tồn tại |

Invoice đã tồn tại: chỉ cập nhật các trường được gửi; `{}` xác nhận mà không sửa trường nghiệp vụ. Invoice chưa tồn tại: phải gửi đủ 5 trường bắt buộc; `{}` bị lỗi validation. Cần extraction result đã tồn tại trong cả hai trường hợp. Không có whitelist mã tiền tệ hoặc định dạng taxCode riêng trong validation hiện tại.

Request mẫu đầy đủ và cURL:

```bash
curl --request PATCH 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111/extraction-result' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{"sellerName":"Cong ty ABC","sellerAddress":"123 Nguyen Hue, TP HCM","invoiceNumber":"HD-001","taxCode":"0312345678","invoiceDate":"2026-10-01","totalAmount":1500000,"currency":"VND"}'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "extraction_result": {
      "id": "44444444-4444-4444-8444-444444444444",
      "document_id": "11111111-1111-4111-8111-111111111111",
      "ai_status": "SUCCESS",
      "retry_count": 0,
      "human_review_required": false,
      "overall_confidence": 0.85,
      "raw_json": {},
      "error_code": null,
      "error_message": null,
      "processing_started_at": "2026-10-05T02:01:00Z",
      "processing_finished_at": "2026-10-05T02:02:00Z"
    },
    "invoice": {
      "id": "55555555-5555-4555-8555-555555555555",
      "document_id": "11111111-1111-4111-8111-111111111111",
      "extraction_result_id": "44444444-4444-4444-8444-444444444444",
      "seller_name": "Cong ty ABC",
      "seller_address": "123 Nguyen Hue, TP HCM",
      "invoice_number": "HD-001",
      "tax_code": "0312345678",
      "invoice_date": "2026-10-01",
      "total_amount": 1500000,
      "currency": "VND",
      "approved_by": 1,
      "approved_at": "2026-10-05T03:00:00Z"
    }
  }
}
```

Request xác nhận invoice đã tồn tại:

```bash
curl --request PATCH 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111/extraction-result' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{}'
```

Request sửa một trường và xóa sellerAddress:

```bash
curl --request PATCH 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111/extraction-result' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json' \
  --data-raw '{"totalAmount":1800000,"sellerAddress":null}'
```

Hai trường hợp trên trả cùng cấu trúc response đầy đủ, với dữ liệu tương ứng sau cập nhật. Thành công cập nhật `approved_by` và `approved_at`, đặt AI SUCCESS, `human_review_required=false`, tài liệu EXTRACT_SUCCESS và mọi review task liên quan thành COMPLETED trong transaction. Các trường raw_json, confidence và lỗi AI cũ không được xóa/sửa bởi thao tác này.

Sai trường hoặc thiếu trường khi tạo invoice: 400 `VALIDATION_ERROR`; UUID sai: 400; tài liệu không thấy: 404 `DOCUMENT_NOT_FOUND`; extraction result thiếu: 404 `EXTRACTION_RESULT_NOT_FOUND`; JWT thiếu/sai/hết hạn: 401.

## 5. Review Tasks

### 5.1. Danh sách tác vụ review

**GET** `http://localhost:8080/api/v1/review-tasks`

Không có body.

| Query | Kiểu | Mặc định/validation |
|---|---|---|
| status | enum | Tùy chọn: PENDING, COMPLETED, CANCELLED |
| page | integer | 1; tối thiểu 1 |
| page_size | integer | 20; từ 1 đến 100 |

Chỉ lấy task của tài liệu ACTIVE đang REVIEW_REQUIRED, dù có lọc status COMPLETED/CANCELLED. Sắp xếp priority tăng dần, rồi createdAt tăng dần. Task của tài liệu vừa được PATCH chuyển EXTRACT_SUCCESS không còn xuất hiện ở danh sách này.

```bash
curl --request GET 'http://localhost:8080/api/v1/review-tasks?status=PENDING&page=1&page_size=20' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [
      {"id": "66666666-6666-4666-8666-666666666666", "document_filename": "invoice.pdf", "vendor_name": "Cong ty ABC", "created_at": "2026-10-05T02:02:00Z", "reason": "LOW_CONFIDENCE", "status": "PENDING", "priority": 1}
    ],
    "total": 1,
    "page": 1,
    "page_size": 20
  }
}
```

`id` là ID review task, không phải documentId. `vendor_name` có thể null khi chưa có invoice. `reason` là chuỗi lưu trong database; `LOW_CONFIDENCE` là ví dụ, không phải enum được định nghĩa. Không có kết quả: `items: []`, `total: 0`. Sai status/phân trang: 400; JWT thiếu/sai/hết hạn: 401.

## 6. Export Jobs

### 6.1. Preview số lượng hóa đơn

**POST** `http://localhost:8080/api/v1/export-jobs/preview`

Controller nhận `@ModelAttribute`: dùng **query parameters** như cURL bên dưới, hoặc form `application/x-www-form-urlencoded`. Không gửi bộ lọc dưới dạng JSON body.

| Query/form | Kiểu | Mặc định/validation |
|---|---|---|
| dateFrom | string date | Tùy chọn, `YYYY-MM-DD`; null/blank/chuỗi `null` (không phân biệt hoa/thường) được bỏ qua |
| dateTo | string date | Tùy chọn, `YYYY-MM-DD`; xử lý giá trị trống như dateFrom |
| status | enum | ALL; chấp nhận ALL, APPROVED, PENDING_REVIEW |

Khoảng ngày lọc theo **invoiceDate**, gồm cả hai đầu; dateFrom không được sau dateTo. Chỉ đếm invoice của tài liệu ACTIVE ở EXTRACT_SUCCESS hoặc REVIEW_REQUIRED. APPROVED tương ứng EXTRACT_SUCCESS; PENDING_REVIEW tương ứng REVIEW_REQUIRED.

Request dùng query:

```bash
curl --request POST 'http://localhost:8080/api/v1/export-jobs/preview?dateFrom=2026-10-01&dateTo=2026-10-05&status=ALL' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Request tương đương bằng form body:

```bash
curl --request POST 'http://localhost:8080/api/v1/export-jobs/preview' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json' \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'dateFrom=2026-10-01' \
  --data-urlencode 'dateTo=2026-10-05' \
  --data-urlencode 'status=ALL'
```

Response mẫu — HTTP 200, status=ALL:

```json
{"status": 0, "error": null, "data": {"total_invoices": 5, "approved": 2, "pending_review": 3}}
```

Request chỉ đếm đã duyệt:

```bash
curl --request POST 'http://localhost:8080/api/v1/export-jobs/preview?status=APPROVED' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{"status": 0, "error": null, "data": {"total_invoices": 2, "approved": 2, "pending_review": null}}
```

Request chỉ đếm chờ duyệt:

```bash
curl --request POST 'http://localhost:8080/api/v1/export-jobs/preview?status=PENDING_REVIEW' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Accept: application/json'
```

Response mẫu — HTTP 200:

```json
{"status": 0, "error": null, "data": {"total_invoices": 3, "approved": null, "pending_review": 3}}
```

Không có invoice phù hợp trả số đếm 0; nhóm không được chọn vẫn null. Ngày sai, khoảng ngày ngược hoặc status sai: 400; JWT thiếu/sai/hết hạn: 401. API không tạo export job, không xuất XLSX/CSV và không trả URL download.

## 7. Response lỗi

Các lỗi được `GlobalExceptionHandler` xử lý có cấu trúc:

```json
{"status": 1, "error": {"code": "VALIDATION_ERROR", "message": "Email is required"}, "data": null}
```

| HTTP | error.code | error.message / tình huống |
|---|---|---|
| 400 | VALIDATION_ERROR | Thông báo validation trường đầu tiên, hoặc lỗi bộ lọc/cập nhật |
| 401 | AUTH_INVALID_CREDENTIALS | `Email or password is incorrect`, hoặc `Account not found` cho tài khoản `INACTIVE` |
| 404 | ACCOUNT_NOT_FOUND | `Account not found`; bao gồm tài khoản `INACTIVE` khi xem chi tiết |
| 403 | ACCESS_DENIED | `Only managers can create accounts` |
| 404 | DOCUMENT_NOT_FOUND | `Document not found` |
| 404 | EXTRACTION_RESULT_NOT_FOUND | `Extraction result not found` |
| 409 | EMAIL_ALREADY_EXISTS | `Email is already registered` |
| 500 | DOCUMENT_UPLOAD_FAILED | `Không thể tải lên tài liệu. Toàn bộ batch đã được rollback` |

Ví dụ request đăng nhập thất bại và response:

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data-raw '{"email":"manager@example.com","password":"wrong-password"}'
```

HTTP 401:

```json
{"status": 1, "error": {"code": "AUTH_INVALID_CREDENTIALS", "message": "Email or password is incorrect"}, "data": null}
```

Ví dụ documentId sai định dạng:

```bash
curl --request GET 'http://localhost:8080/api/v1/documents/not-a-uuid' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>'
```

HTTP 400:

```json
{"status": 1, "error": {"code": "VALIDATION_ERROR", "message": "Invalid parameter: documentId"}, "data": null}
```

Ví dụ PATCH số tiền không hợp lệ (với tài liệu và extraction result đã tồn tại):

```bash
curl --request PATCH 'http://localhost:8080/api/v1/documents/11111111-1111-4111-8111-111111111111/extraction-result' \
  --header 'Authorization: Bearer <ACCESS_TOKEN>' \
  --header 'Content-Type: application/json' \
  --data-raw '{"totalAmount":0}'
```

HTTP 400:

```json
{"status": 1, "error": {"code": "VALIDATION_ERROR", "message": "totalAmount must be greater than 0"}, "data": null}
```

JWT thiếu, sai chữ ký, hết hạn, đã bị thu hồi hoặc thuộc tài khoản `INACTIVE` bị Spring Security chặn trước controller và trả 401; mã nguồn chưa cấu hình JSON `BaseResponse` riêng cho trường hợp đó. JSON sai cú pháp hoặc enum sai trong JSON trả 400 `VALIDATION_ERROR`, message `Invalid request body`. Lỗi multipart chưa có handler riêng trong `GlobalExceptionHandler`; không coi cấu trúc lỗi mẫu trên là cam kết cho trường hợp này.

## 8. Nguồn đối chiếu

- Routes và HTTP methods: `src/main/java/org/involens_be/controller/`.
- Request fields/validation: `src/main/java/org/involens_be/dto/request/`.
- Response fields/JSON names: `src/main/java/org/involens_be/dto/response/`.
- Phân quyền và nghiệp vụ: `src/main/java/org/involens_be/service/` và `repository/`.
- JWT và truy cập công khai: `src/main/java/org/involens_be/config/SecurityConfig.java`.
- Mã lỗi: `src/main/java/org/involens_be/exception/GlobalExceptionHandler.java`.
- Enum trạng thái: `src/main/java/org/involens_be/common/`.

## Development mock invoices

With the `dev` profile, `MOCK_INVOICES_ENABLED=true` (default) seeds ten synthetic
invoices after startup for the active MANAGER configured by `app.admin.email`
(`ADMIN_EMAIL`, default `quan@involens.com`). A missing, inactive, or non-manager
account causes seeding to be skipped with a log message.

- Five documents have `REVIEW_REQUIRED`, AI status `REVIEW_REQUIRED`, and `PENDING`
  review tasks. They include low-confidence recognition and some absent addresses.
- Five documents have `EXTRACT_SUCCESS` and AI status `SUCCESS`.
- Each invoice has synthetic seller, date, VND amounts, and one invoice line.
- Filenames are `mock-invoice-dev-1.pdf` through `mock-invoice-dev-10.pdf`.
  These are data-only samples: no MinIO object is created and `file_url` is null.

Log in as the configured manager and use the existing document list, review task
list, and `GET /api/v1/documents/{documentId}/extraction-result` APIs. Submit review
corrections through `PATCH /api/v1/documents/{documentId}/extraction-result`.
Seeding uses the normal AI persistence service without calling an AI worker or
RabbitMQ. Existing sample filenames for this manager are skipped, including
reviewed or inactive documents; restarting does not overwrite FE changes.
The batch rolls back on persistence failure. Set `MOCK_INVOICES_ENABLED=false`
to disable future seeding; existing samples remain available.

## Dedicated extracted invoice list

`GET /api/v1/invoices` requires a bearer access token. Managers see all active
documents in the selected extraction states; employees see only their uploads.
This endpoint is independent of the document list API.

| Query | Default | Accepted values |
|---|---|---|
| `q` | No filename filter | Case-insensitive substring of the original filename |
| `date_from` | No lower bound | Invoice date >= this date, `YYYY-MM-DD` |
| `date_to` | No upper bound | Invoice date <= this date, `YYYY-MM-DD` |
| `current_state` | All three states | `EXTRACT_SUCCESS`, `REVIEW_REQUIRED`, `EXTRACT_FAILED` |
| `page` | `1` | Integer >= 1 |
| `page_size` | `8` | Integer from 1 to 100 |

Results are ordered by upload time descending, then document ID descending.
Date filtering uses `invoice_date`, includes both boundaries and accepts either
boundary independently. Documents with no invoice date are excluded when any
date boundary is supplied, and retained otherwise. Reversed ranges and invalid
dates return HTTP 400 with `VALIDATION_ERROR`. Date filters combine with all
other filters and apply to the total count as well as the returned page.
Filename search trims leading/trailing whitespace and ignores empty or blank `q`.
The characters `%`, `_`, and `!` are matched literally. Search combines with
the state and ownership filters and applies to both the page and its total count.
`id` is the invoice ID and can be null when extraction failed before an invoice
was created. `document_id` is always present and can be used with the existing
document detail and extraction-result endpoints. `seller_name` comes from the
saved invoice, including any human corrections. Error fields come from the
document. Invoice fields remain null when unavailable. Empty selections return
HTTP 200 with `items: []` and `total: 0`. Invalid query values return HTTP 400
with the existing `VALIDATION_ERROR` envelope. Missing or invalid JWTs return 401.

The following commands are each one line and work with `curl.exe` in PowerShell.
Replace `<ACCESS_TOKEN>` with the token returned by login.

```shell
curl.exe "http://localhost:8080/api/v1/invoices?page=1&page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
curl.exe -G "http://localhost:8080/api/v1/invoices" --data-urlencode "q=mock-invoice-dev-6" --data-urlencode "current_state=EXTRACT_SUCCESS" --data-urlencode "page=1" --data-urlencode "page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
curl.exe -G "http://localhost:8080/api/v1/invoices" --data-urlencode "q=mock-invoice" --data-urlencode "date_from=2026-10-01" --data-urlencode "date_to=2026-10-15" --data-urlencode "page=1" --data-urlencode "page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
curl.exe "http://localhost:8080/api/v1/invoices?current_state=EXTRACT_SUCCESS&page=1&page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
curl.exe "http://localhost:8080/api/v1/invoices?current_state=REVIEW_REQUIRED&page=1&page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
curl.exe "http://localhost:8080/api/v1/invoices?current_state=EXTRACT_FAILED&page=1&page_size=8" -H "Authorization: Bearer <ACCESS_TOKEN>"
```

Illustrative all-states response; identifiers and timestamps are examples, not
live database values. The existing development seed creates success and review
samples only; failed outcomes appear when such documents exist in the database.

```json
{
  "status": 0,
  "error": null,
  "data": {
    "items": [
      {
        "id": "bcbd7352-9eb3-4bad-8357-3dd64e957110",
        "document_id": "5ca79f91-47be-4ca7-b314-4497d6d09e21",
        "original_filename": "mock-invoice-dev-6.pdf",
        "seller_name": "Cua hang mau 6",
        "invoice_number": null,
        "invoice_date": "2026-10-06",
        "total_amount": 300000.00,
        "currency": "VND",
        "uploaded_by_name": "Nguyen Hoang Quan",
        "uploaded_at": "2026-10-15T07:00:00Z",
        "current_state": "EXTRACT_SUCCESS",
        "error_code": null,
        "error_message": null
      },
      {
        "id": "bcbd7352-9eb3-4bad-8357-3dd64e957111",
        "document_id": "5ca79f91-47be-4ca7-b314-4497d6d09e22",
        "original_filename": "mock-invoice-dev-1.pdf",
        "seller_name": "Cua hang mau 1",
        "invoice_number": null,
        "invoice_date": "2026-10-01",
        "total_amount": 50000.00,
        "currency": "VND",
        "uploaded_by_name": "Nguyen Hoang Quan",
        "uploaded_at": "2026-10-15T06:59:00Z",
        "current_state": "REVIEW_REQUIRED",
        "error_code": null,
        "error_message": null
      },
      {
        "id": null,
        "document_id": "5ca79f91-47be-4ca7-b314-4497d6d09e23",
        "original_filename": "failed-invoice.pdf",
        "seller_name": null,
        "invoice_number": null,
        "invoice_date": null,
        "total_amount": null,
        "currency": null,
        "uploaded_by_name": "Nguyen Hoang Quan",
        "uploaded_at": "2026-10-15T06:58:00Z",
        "current_state": "EXTRACT_FAILED",
        "error_code": "IMAGE_DOWNLOAD_FAILED",
        "error_message": "MinIO unavailable"
      }
    ],
    "total": 3,
    "page": 1,
    "page_size": 8
  }
}
```
