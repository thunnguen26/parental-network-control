package com.parental.client.activity;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Bộ thu thập hoạt động người dùng (Activity Monitoring — PROTOCOL.md Mục
 * 3.9): tiêu đề/tiến trình cửa sổ đang focus + danh sách tiến trình tiêu biểu.
 *
 * <p><b>Contract bắt buộc</b> (AGENTS.md — Defensive OS Interfacing):
 * {@link #collect()} KHÔNG BAO GIỜ ném exception và KHÔNG BAO GIỜ trả
 * {@code null} — môi trường không hỗ trợ (headless, test, hệ không Windows,
 * PowerShell bị chặn...) trả về payload rỗng an toàn. Tách interface để test
 * stub được và scheduler của engine không bao giờ chết vì collector.</p>
 */
@FunctionalInterface
public interface ActivityCollector {

    /** Trạng thái khi không xác định được cửa sổ/tiến trình (payload rỗng). */
    String UNKNOWN = "";

    /**
     * Thu thập hoạt động hiện tại.
     *
     * @return {@link ActivityReportPayload} (không null — rỗng khi không thu được)
     */
    ActivityReportPayload collect();
}
