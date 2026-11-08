package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.VendorItemReportRequest;
import org.involens_be.dto.response.VendorItemReportResponse;
import org.involens_be.dto.response.VendorItemReportResponse.Item;
import org.involens_be.exception.InvalidVendorItemReportException;
import org.involens_be.exception.InvoiceExportException;
import org.involens_be.repository.VendorItemReportRepository;
import org.involens_be.repository.VendorItemReportRepository.ReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorItemReportService {
    private final VendorItemReportRepository repository;
    private final VendorItemReportExcelWriter excelWriter;

    /** Summarizes successful supplier invoices within the caller's upload visibility and inclusive dates. */
    @Transactional(readOnly = true)
    public VendorItemReportResponse report(VendorItemReportRequest request, Long userId, UserRole role) {
        log.info("Starting supplier item report. vendorId={}, userId={}, role={}", request.getVendorId(), userId, role);
        if (request.getVendorId() == null) {
            throw new InvalidVendorItemReportException("vendorId is required");
        }
        LocalDate from = parseDate(request.getDateFrom(), "dateFrom");
        LocalDate to = parseDate(request.getDateTo(), "dateTo");
        if (from.isAfter(to)) {
            throw new InvalidVendorItemReportException("dateFrom must be on or before dateTo");
        }
        boolean manager = role == UserRole.MANAGER;
        log.debug("Applying supplier report visibility. allUploads={}", manager);
        List<ReportRow> rows = repository.findReportRows(request.getVendorId(), from, to, manager, userId);
        Map<String, List<ReportRow>> groups = new HashMap<>();
        Set<UUID> invoices = new HashSet<>();
        BigDecimal revenue = BigDecimal.ZERO;
        for (ReportRow row : rows) {
            invoices.add(row.invoiceId());
            if (row.itemId() != null) {
                groups.computeIfAbsent(row.itemName().trim().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(row);
                revenue = revenue.add(row.lineTotal());
            }
        }
        List<Item> items = new ArrayList<>();
        for (List<ReportRow> group : groups.values()) {
            items.add(summarize(group, revenue));
        }
        items.sort(Comparator.comparing(Item::totalAmount).reversed().thenComparing(Item::itemName));
        log.info("Supplier item report completed. invoices={}, items={}", invoices.size(), items.size());
        return new VendorItemReportResponse(revenue, invoices.size(), items.size(), items);
    }

    /** Exports the same supplier selection and aggregates used by the screen, including empty selections. */
    @Transactional(readOnly = true)
    public byte[] exportExcel(VendorItemReportRequest request, Long userId, UserRole role) {
        log.info("Starting supplier item Excel export. vendorId={}, userId={}", request.getVendorId(), userId);
        VendorItemReportResponse response = report(request, userId, role);
        try {
            byte[] bytes = excelWriter.write(request, response);
            log.info("Supplier item Excel export completed. bytes={}", bytes.length);
            return bytes;
        } catch (IOException | RuntimeException exception) {
            log.error("Supplier item Excel export failed. vendorId={}", request.getVendorId(), exception);
            throw new InvoiceExportException(exception);
        }
    }

    /** Calculates quantity, weighted price, observed price range and revenue share for one normalized item name. */
    private Item summarize(List<ReportRow> rows, BigDecimal revenue) {
        log.trace("Aggregating supplier item. rows={}", rows.size());
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal min = rows.getFirst().unitPrice();
        BigDecimal max = min;
        String name = rows.getFirst().itemName().trim();
        for (ReportRow row : rows) {
            quantity = quantity.add(new BigDecimal(row.quantity().toString()));
            amount = amount.add(row.lineTotal());
            min = min.min(row.unitPrice());
            max = max.max(row.unitPrice());
        }
        BigDecimal price = amount.divide(quantity, 2, RoundingMode.HALF_UP);
        BigDecimal percentage = revenue.signum() == 0 ? BigDecimal.ZERO
                : amount.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
        return new Item(name, quantity, price, min, max, amount, percentage);
    }

    /** Parses a required ISO invoice date and reports malformed or omitted filters as client errors. */
    private LocalDate parseDate(String value, String field) {
        log.debug("Parsing supplier report date. field={}", field);
        if (value == null || value.isBlank()) {
            throw new InvalidVendorItemReportException(field + " is required");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidVendorItemReportException(field + " must use YYYY-MM-DD format");
        }
    }
}
