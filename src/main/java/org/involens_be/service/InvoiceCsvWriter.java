package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.exception.NoInvoicesToExportException;
import org.involens_be.repository.ExportJobRepository.InvoiceExportRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.IntFunction;

@Slf4j
@Component
public class InvoiceCsvWriter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Exports the selected invoices in database batches as a UTF-8 CSV attachment with Vietnamese headers. */
    public byte[] write(IntFunction<List<InvoiceExportRow>> loadBatch) throws IOException {
        log.info("Starting invoice CSV creation");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("STT,Ngày hóa đơn,Nhà cung cấp,Địa chỉ nhà cung cấp,Tổng tiền,Trạng thái\r\n");
            long count = 0;
            int batchIndex = 0;
            List<InvoiceExportRow> batch;
            while (!(batch = loadBatch.apply(batchIndex++)).isEmpty()) {
                for (InvoiceExportRow invoice : batch) {
                    writeInvoice(writer, invoice, ++count);
                }
                log.debug("Invoice CSV batch written. batch={}, totalRows={}", batchIndex, count);
            }
            if (count == 0) {
                throw new NoInvoicesToExportException();
            }
            writer.flush();
            log.info("Invoice CSV created successfully. rows={}", count);
            return output.toByteArray();
        }
    }

    /** Writes one invoice summary, preserving missing values and escaping supplier text for CSV readers. */
    private void writeInvoice(OutputStreamWriter writer, InvoiceExportRow invoice, long ordinal) throws IOException {
        String[] values = {
                Long.toString(ordinal),
                invoice.invoiceDate() == null ? "" : DATE_FORMAT.format(invoice.invoiceDate()),
                invoice.sellerName(),
                invoice.sellerAddress(),
                invoice.totalAmount() == null ? "" : invoice.totalAmount().toPlainString(),
                invoice.state() == DocumentState.EXTRACT_SUCCESS ? "Đã duyệt" : "Chờ duyệt"
        };
        for (int column = 0; column < values.length; column++) {
            if (column > 0) {
                writer.write(',');
            }
            String value = values[column];
            if (value != null) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            }
        }
        writer.write("\r\n");
        log.trace("Invoice written to CSV. invoiceId={}, ordinal={}", invoice.id(), ordinal);
    }
}
