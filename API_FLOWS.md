# API flows

Tài liệu này mô tả ngắn gọn luồng xử lý của toàn bộ API hiện có trong dự án.

## 1. Quy ước chung

- Base path: `/api/v1`.
- Trừ API đăng nhập, mọi API đều yêu cầu header `Authorization: Bearer <access_token>`.
- ID document, extraction result và review task dùng UUID.
- Response thành công có dạng:

```json
{
  "status": 0,
  "data": {}
}
```

- Response lỗi nghiệp vụ có dạng:

```json
{
  "status": 1,
  "error": {
    "code": "ERROR_CODE",
    "message": "Error description"
  },
  "data": null
}
```

- Timestamp trả về từ document, extraction result và review task được biểu diễn theo UTC.
- JWT do hệ thống cấp chứa `userId` và `role`. Role hiện có: `EMPLOYEE`, `MANAGER`.

## 2. Authentication APIs

### POST `/api/v1/auth/login`

Xác thực tài khoản và cấp JWT.

Request:

```json
{
  "email": "user@example.com",
  "password": "password"
}
```

Luồng xử lý:

1. Kiểm tra email và password không rỗng, email đúng định dạng.
2. Tìm user theo email, không phân biệt hoa thường.
3. Từ chối tài khoản `INACTIVE` với HTTP 401, message `Account not found`.
4. Kiểm tra password bằng BCrypt cho tài khoản hoạt động.
5. Tạo JWT chứa định danh và role của user, sau đó trả token cùng thông tin user.

Lỗi chính:

- `400 VALIDATION_ERROR`: request không hợp lệ.
- `401 AUTH_INVALID_CREDENTIALS`: email hoặc password sai.
- `401 AUTH_INVALID_CREDENTIALS`, message `Account not found`: tài khoản đã xóa mềm (`INACTIVE`).

```bash
curl --request POST '/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data '{"email":"user@example.com","password":"password"}'
```

### GET `/api/v1/auth/me`

Trả thông tin user đang đăng nhập.

Luồng xử lý:

1. Xác thực JWT.
2. Dùng subject/email trong token để tìm user hiện tại.
3. Từ chối nếu tài khoản đã xóa mềm (`INACTIVE`).
4. Trả `id`, `email`, `fullName` và `role`.

Lỗi chính: `401` nếu JWT không hợp lệ, đã bị thu hồi hoặc tài khoản đã xóa mềm.

```bash
curl '/api/v1/auth/me' --header 'Authorization: Bearer <access_token>'
```

### POST `/api/v1/auth/logout`

Xác nhận đăng xuất trong cơ chế JWT stateless. Backend không lưu hoặc thu hồi token; client phải tự xóa token.

```bash
curl --request POST '/api/v1/auth/logout' \
  --header 'Authorization: Bearer <access_token>'
```

### DELETE `/api/v1/auth/accounts/{id}`

MANAGER xóa mềm tài khoản theo ID, không có body. Service khóa cùng tập tài khoản như PATCH, bảo vệ manager hoạt động cuối cùng, chuyển trạng thái sang `CommonStatus.INACTIVE` và tăng `tokenVersion`. Bản ghi vẫn tồn tại. Nếu tài khoản đã `INACTIVE`, trả thành công mà không thay đổi tiếp.

HTTP 200: `{"status":0,"error":null,"data":null}`. Lỗi: 400 nếu xóa manager hoạt động cuối cùng, 403 nếu không phải MANAGER, 404 nếu ID không tồn tại, 401 nếu JWT không hợp lệ.

### PATCH `/api/v1/auth/accounts/{id}`

MANAGER cập nhật `role` (`EMPLOYEE`/`MANAGER`) và/hoặc `status` (`ACTIVE`/`INACTIVE`). Không nhận body rỗng hoặc trường `null`; bảo vệ manager hoạt động cuối cùng. Có thể khôi phục tài khoản theo ID bằng `{"status":"ACTIVE"}`; token cũ vẫn không sử dụng được.

### GET `/api/v1/auth/accounts` và GET `/api/v1/auth/accounts/{id}`

Chỉ MANAGER được gọi. Danh sách và tổng số bản ghi chỉ bao gồm `ACTIVE`. Chi tiết trả 404 `ACCOUNT_NOT_FOUND` cho ID không tồn tại hoặc tài khoản `INACTIVE`. URL, request và response đầy đủ nằm trong `API_DOCUMENTATION.md`, mục 3.5–3.8.

## 3. Document APIs

### GET `/api/v1/documents`

Trả danh sách document `ACTIVE`. `EMPLOYEE` chỉ xem document do mình upload; `MANAGER` xem toàn bộ.

Query parameters:

| Tham số | Ý nghĩa |
| --- | --- |
| `current_state` | Lọc theo trạng thái document |
| `filename` | Tìm filename, không phân biệt hoa thường |
| `uploaded_by` | Manager lọc theo uploader; Employee luôn bị giới hạn theo chính mình |
| `date_from` | Ngày upload bắt đầu, định dạng `YYYY-MM-DD` |
| `date_to` | Ngày upload kết thúc, định dạng `YYYY-MM-DD` |
| `q` | Tìm filename, không phân biệt hoa thường |
| `page` | Trang bắt đầu từ 1, mặc định 1 |
| `page_size` | Số phần tử, mặc định 8, tối đa 100 |

Các filter được kết hợp bằng `AND`. Khoảng ngày được tính theo múi giờ `Asia/Ho_Chi_Minh` và bao gồm cả hai ngày. Kết quả sắp xếp theo `uploaded_at DESC`, sau đó `id DESC`.

```bash
curl -G '/api/v1/documents' \
  --header 'Authorization: Bearer <access_token>' \
  --data-urlencode 'current_state=REVIEW_REQUIRED' \
  --data-urlencode 'page=1' \
  --data-urlencode 'page_size=8'
```

### GET `/api/v1/documents/{documentId}`

Trả chi tiết một document.

Luồng xử lý:

1. Xác thực JWT và đọc `userId`.
2. Tìm document `ACTIVE`; Employee phải là uploader, Manager được xem document của mọi user.
3. Lấy tên uploader và trả metadata document.

Document không tồn tại/inactive, hoặc Employee truy cập document của user khác, đều trả `404 DOCUMENT_NOT_FOUND`.

```bash
curl '/api/v1/documents/<document_uuid>' \
  --header 'Authorization: Bearer <access_token>'
```

### GET `/api/v1/documents/{documentId}/extraction-result`

Trả kết quả AI extraction. Employee chỉ truy cập document của mình; Manager truy cập được mọi document.

Luồng xử lý:

1. Kiểm tra document `ACTIVE` và thuộc user hiện tại.
2. Tìm extraction result theo `documentId`.
3. Trả trạng thái AI, confidence, raw JSON, số lần retry, yêu cầu human review, thời gian xử lý và thông tin lỗi AI.

Phân biệt lỗi:

- Lỗi AI là kết quả nghiệp vụ: HTTP `200`, `status: 0`, `data.ai_status: "FAILED"`; chi tiết nằm trong `data.error_code` và `data.error_message`.
- Document không truy cập được: `404 DOCUMENT_NOT_FOUND`.
- Document hợp lệ nhưng chưa có kết quả: `404 EXTRACTION_RESULT_NOT_FOUND`.
- UUID không hợp lệ: `400 VALIDATION_ERROR`.

```bash
curl '/api/v1/documents/<document_uuid>/extraction-result' \
  --header 'Authorization: Bearer <access_token>'
```

### POST `/api/v1/documents/upload`

Upload một hoặc nhiều file bằng multipart field `files`.

Quy tắc:

- Hỗ trợ JPG, JPEG, PNG và PDF.
- Mỗi file tối đa 10 MB.
- Kiểm tra đồng thời extension và MIME type.
- File rỗng, quá lớn hoặc sai định dạng được lưu thành document có `upload_status: FAILED` cùng mã lỗi tương ứng.
- File hợp lệ được upload lên MinIO, tính SHA-256 và lưu metadata.
- Nếu storage hoặc database gặp lỗi hệ thống, toàn bộ batch được rollback và các object đã upload trong batch được xóa bù trừ.

Mã validation theo từng file: `EMPTY_FILE`, `FILE_TOO_LARGE`, `UNSUPPORTED_FORMAT`. Lỗi toàn batch trả `500 DOCUMENT_UPLOAD_FAILED`.

```bash
curl --request POST '/api/v1/documents/upload' \
  --header 'Authorization: Bearer <access_token>' \
  --form 'files=@invoice.pdf' \
  --form 'files=@receipt.jpg'
```

## 4. Review Task APIs

### GET `/api/v1/review-tasks`

Trả danh sách hóa đơn cần human review.

Query parameters:

| Tham số | Ý nghĩa |
| --- | --- |
| `status` | Tùy chọn: `PENDING`, `COMPLETED`, `CANCELLED` |
| `page` | Trang bắt đầu từ 1, mặc định 1 |
| `page_size` | Số phần tử, mặc định 20, tối đa 100 |

Luồng xử lý:

1. Đọc `userId` và `role` từ JWT.
2. Chỉ lấy task gắn với document `ACTIVE` và `REVIEW_REQUIRED`.
3. `EMPLOYEE` chỉ thấy task của document do mình upload; `MANAGER` thấy toàn bộ task.
4. Áp dụng status nếu được truyền.
5. Left join invoice để lấy `seller_name` làm `vendor_name`; chưa có invoice thì trả `null`.
6. Sắp xếp `priority ASC`, sau đó `created_at ASC` và phân trang.

Mỗi item gồm `id`, `document_filename`, `vendor_name`, `created_at`, `reason`, `status` và `priority`.

```bash
curl -G '/api/v1/review-tasks' \
  --header 'Authorization: Bearer <access_token>' \
  --data-urlencode 'status=PENDING' \
  --data-urlencode 'page=1' \
  --data-urlencode 'page_size=20'
```

## 5. Export Preview API

### POST `/api/v1/export-jobs/preview`

Returns invoice counts before an export job is created. Employees only see invoices from documents
they uploaded; managers see all active documents.

| Parameter | Meaning |
| --- | --- |
| `dateFrom` | Optional inclusive invoice date (`YYYY-MM-DD`); omitted or literal `null` means no lower bound |
| `dateTo` | Optional inclusive invoice date (`YYYY-MM-DD`); omitted or literal `null` means no upper bound |
| `status` | `ALL` (default), `APPROVED`, or `PENDING_REVIEW` |

An invoice is `APPROVED` when its document state is `EXTRACT_SUCCESS`. It is `PENDING_REVIEW`
when its document state is `REVIEW_REQUIRED`. Other document states are excluded from export counts.
For a single-status filter, the other status count is null.

```bash
curl --location --request POST \
  '/api/v1/export-jobs/preview?dateFrom=null&dateTo=null&status=ALL' \
  --header 'Authorization: Bearer <access_token>'
```

```json
{
  "status": 0,
  "data": {
    "total_invoices": 245,
    "approved": 198,
    "pending_review": 47
  }
}
```

## 6. Mã lỗi API

| HTTP | Code | Ý nghĩa |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Path, query hoặc body không hợp lệ |
| 401 | `AUTH_INVALID_CREDENTIALS` | Email hoặc password không đúng |
| 401 | Do Spring Security xử lý | JWT thiếu, hết hạn hoặc không hợp lệ |
| 401 | `AUTH_INVALID_CREDENTIALS` | Tài khoản `INACTIVE`: `Account not found` |
| 404 | `ACCOUNT_NOT_FOUND` | Tài khoản không tồn tại hoặc đã xóa mềm khi xem chi tiết |
| 404 | `DOCUMENT_NOT_FOUND` | Document không tồn tại, inactive hoặc không thuộc user |
| 404 | `EXTRACTION_RESULT_NOT_FOUND` | Document chưa có extraction result |
| 500 | `DOCUMENT_UPLOAD_FAILED` | Batch upload thất bại do storage hoặc persistence |
