package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.swing.DefaultListModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.server.db.BlockedDomainDao;
import com.parental.server.db.ClientDao;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.DatabaseManager;
import com.parental.server.db.RuleDao;

/**
 * Unit test HEADLESS cho logic Quản lý chặn tiến trình của
 * {@link PolicyConfigDialog} (Mở rộng 12) qua {@link PolicyConfigDialogTestHook}
 * (logic thuần tách khỏi Swing — surefire chạy {@code java.awt.headless=true}
 * nên không dựng được JDialog thật, nhưng Swing model là object thuần):
 * <ul>
 *   <li><b>Tự tải khi mở dialog</b>: {@code loadProcesses} nạp từ DB của
 *       thiết bị tương ứng (cả null-DAO defensive);</li>
 *   <li><b>Thêm / Bỏ chặn</b>: chuẩn hóa → thêm (khử trùng), remove index;</li>
 *   <li><b>Lưu &amp; Đồng bộ</b>: {@code snapshot} gom đúng danh sách cả 2 tab
 *       — domains lẫn blockedProcesses lên đường persist + CONFIG_SYNC.</li>
 * </ul>
 */
class PolicyConfigDialogTestHookTest {

    private DatabaseManager database;
    private RuleDao ruleDao;
    private ClientDao clientDao;
    private BlockedDomainDao blockedDomainDao;

    @BeforeEach
    void setUp() {
        database = new DatabaseManager(); // :memory:
        clientDao = new ClientDao(database);
        ruleDao = new RuleDao(database);
        blockedDomainDao = new BlockedDomainDao(database);
        clientDao.upsertClient(new ClientRecord(
                "client-gui", "PC Con", "DESKTOP-ABC", "Windows 11", "127.0.0.1",
                "AA:BB", "ONLINE"));
    }

    @Test
    @DisplayName("MỞ DIALOG: tự tải danh sách tiến trình + domain bị chặn từ DB của thiết bị")
    void loadProcessesFromDao() {
        ruleDao.addBlockedProcess("client-gui", "SomeGame");
        ruleDao.addBlockedProcess("client-gui", "browser.exe");
        blockedDomainDao.addDomain("client-gui", "youtube.com");

        DefaultListModel<String> processModel = new DefaultListModel<>();
        DefaultListModel<String> domainModel = new DefaultListModel<>();
        PolicyConfigDialogTestHook.loadProcesses(ruleDao, "client-gui", processModel);
        PolicyConfigDialogTestHook.loadDomains(ruleDao, "client-gui", domainModel);

        // Server DAO sort theo process_name — browser trước somegame
        assertEquals(List.of("browser.exe", "somegame.exe"), PolicyConfigDialogTestHook.snapshot(processModel),
                "Danh sách .exe đang bị chặn phải hiện đầy đủ khi mở dialog");
        assertEquals(List.of("youtube.com"), PolicyConfigDialogTestHook.snapshot(domainModel));
    }

    @Test
    @DisplayName("Load defensive: DAO null / client không có gì → model rỗng, không ném")
    void loadDefensive() {
        DefaultListModel<String> model = new DefaultListModel<>();
        PolicyConfigDialogTestHook.loadProcesses(null, "any", model);
        assertTrue(PolicyConfigDialogTestHook.snapshot(model).isEmpty());

        PolicyConfigDialogTestHook.loadProcesses(ruleDao, "ghost-client", model);
        assertTrue(PolicyConfigDialogTestHook.snapshot(model).isEmpty(),
                "Client chưa từng chặn app → danh sách rỗng");
    }

    @Test
    @DisplayName("+ THÊM TIẾN TRÌNH: thêm vào model, khử trùng lặp, từ chối null/rỗng")
    void addProcessDedupes() {
        DefaultListModel<String> model = new DefaultListModel<>();
        assertTrue(PolicyConfigDialogTestHook.addNormalized(model, "game.exe"));
        assertFalse(PolicyConfigDialogTestHook.addNormalized(model, "game.exe"),
                "Trùng → không thêm lần 2");
        assertFalse(PolicyConfigDialogTestHook.addNormalized(model, null));
        assertFalse(PolicyConfigDialogTestHook.addNormalized(model, "   "));
        assertEquals(1, model.size());
    }

    @Test
    @DisplayName("BỎ CHẶN / XÓA: remove đúng dòng, index ngoài phạm vi no-op an toàn")
    void removeIndexUnblocks() {
        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("browser.exe");
        model.addElement("game.exe");

        assertTrue(PolicyConfigDialogTestHook.removeIndex(model, 1));
        assertEquals(List.of("browser.exe"), PolicyConfigDialogTestHook.snapshot(model),
                "Gỡ game.exe khỏi danh sách = bỏ chặn");

        assertFalse(PolicyConfigDialogTestHook.removeIndex(model, 5));
        assertFalse(PolicyConfigDialogTestHook.removeIndex(model, -1));
        assertFalse(PolicyConfigDialogTestHook.removeIndex(null, 0));
        assertEquals(List.of("browser.exe"), PolicyConfigDialogTestHook.snapshot(model),
                "Remove no-op không làm mất dữ liệu");
    }

    @Test
    @DisplayName("LƯU & ĐỒNG BỘ: snapshot gom đúng cả domains lẫn blockedProcesses (REPLACE-ALL)")
    void snapshotCollectsBothListsForSave() {
        ruleDao.addBlockedProcess("client-gui", "oldgame.exe");

        // Giả lập trạng thái model khi bấm Lưu: thêm mới + BỎ app cũ
        DefaultListModel<String> processModel = new DefaultListModel<>();
        processModel.addElement("browser.exe");   // phụ huynh vừa thêm
        // oldgame.exe đã bị remove khỏi model (Bỏ chặn) → KHÔNG còn trong snapshot
        DefaultListModel<String> domainModel = new DefaultListModel<>();
        domainModel.addElement("tiktok.com");

        List<String> savedProcesses = PolicyConfigDialogTestHook.snapshot(processModel);
        List<String> savedDomains = PolicyConfigDialogTestHook.snapshot(domainModel);

        assertEquals(List.of("browser.exe"), savedProcesses,
                "oldgame đã bỏ khỏi model = bỏ chặn — không được persist lại");
        assertEquals(List.of("tiktok.com"), savedDomains);

        // Persist theo đúng luồng saveAndSync → đọc lại từ DB khớp
        com.parental.common.protocol.payload.ConfigSyncPayload config =
                new com.parental.common.protocol.payload.ConfigSyncPayload(
                        savedDomains, 0, 0, null, null, savedProcesses);
        assertTrue(ruleDao.upsertRule("client-gui", config));
        assertEquals(List.of("browser.exe"), ruleDao.getBlockedProcesses("client-gui"));
        assertEquals(List.of("tiktok.com"), blockedDomainDao
                .getBlockedDomains("client-gui"));
    }

    @Test
    @DisplayName("Snapshot defensive: null model → rỗng; phần tử null/rỗng bị bỏ qua")
    void snapshotDefensive() {
        assertTrue(PolicyConfigDialogTestHook.snapshot(null).isEmpty());

        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("good.exe");
        model.addElement(null);
        model.addElement("   ");
        assertEquals(List.of("good.exe"), PolicyConfigDialogTestHook.snapshot(model),
                "Phần tử rác không được lọt vào danh sách lưu DB");
    }

    @Test
    @DisplayName("Quy tắc chuẩn hóa GUI: normalizeProcessName trả đúng dạng lowercase + .exe")
    void guiHelperMatchesDaoNormalization() {
        assertEquals("newgame.exe",
                com.parental.server.gui.BlockedDomainDialogHelper.normalizeProcessName("NewGame"),
                "GUI normalize phải ra dạng giống DAO lưu — không sai lệch khi lưu");
    }
}
