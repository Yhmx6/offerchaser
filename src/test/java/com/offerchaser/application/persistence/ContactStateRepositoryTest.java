package com.offerchaser.application.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static com.offerchaser.application.persistence.PersistenceTestSupport.ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 联系人状态仓库测试：插入/唯一键、乐观锁 updateState、touchLastContact、筛选分页。
 */
class ContactStateRepositoryTest {

    private static final String PLATFORM = "boss";

    @TempDir
    Path directory;

    private ContactStateRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = PersistenceTestSupport.migratedDataSource(directory);
        repository = new ContactStateRepository(dataSource);
    }

    @Test
    void insertAndFindByKey() {
        ContactStateRepository.ContactState inserted = repository.insert(
                state("uid:HR-1", "UNKNOWN", false, false));

        assertTrue(inserted.id() > 0);
        assertEquals(0, inserted.version());
        assertNotNull(inserted.createdAt());
        assertNotNull(inserted.updatedAt());
        assertEquals("uid:HR-1", inserted.contactKey());
        assertEquals("HR-1", inserted.encryptUserId());
        assertEquals("张HR", inserted.hrName());
        assertEquals("甲公司", inserted.companyName());

        ContactStateRepository.ContactState found = repository.findByKey(ACCOUNT, PLATFORM, "uid:HR-1");
        assertNotNull(found);
        assertEquals(inserted, found);
        assertNull(repository.findByKey(ACCOUNT, PLATFORM, "uid:missing"));
    }

    @Test
    void duplicateUniqueKeyIsRejected() {
        repository.insert(state("uid:HR-1", "UNKNOWN", false, false));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.insert(state("uid:HR-1", "REPLIED", false, false)));
        assertTrue(error.getMessage().contains("uid:HR-1"));
    }

    @Test
    void updateStateUsesOptimisticVersionAndReturnsUpdatedRecord() {
        repository.insert(state("uid:HR-1", "UNKNOWN", false, false));

        ContactStateRepository.ContactState updated = repository.updateState(
                ACCOUNT, PLATFORM, "uid:HR-1", "REPLIED", true, false,
                "2026-11-05 10:00:00", 0);

        assertNotNull(updated);
        assertEquals(1, updated.version());
        assertEquals("REPLIED", updated.replyState());
        assertTrue(updated.manualTakeover());
        assertTrue(!updated.stopContact());
        assertEquals("2026-11-05 10:00:00", updated.lastReplyAt());

        // 旧版本号再更新 → 冲突，返回 null
        assertNull(repository.updateState(ACCOUNT, PLATFORM, "uid:HR-1", "REJECTED",
                false, true, null, 0));
        assertEquals("REPLIED", repository.findByKey(ACCOUNT, PLATFORM, "uid:HR-1").replyState(),
                "冲突时不得修改记录");
        // 正确版本号可以继续更新
        assertNotNull(repository.updateState(ACCOUNT, PLATFORM, "uid:HR-1", "REJECTED",
                false, true, null, 1));
    }

    @Test
    void touchLastContactUpdatesTimeWithoutBumpingVersion() {
        repository.insert(state("uid:HR-1", "UNKNOWN", false, false));

        ContactStateRepository.ContactState touched = repository.touchLastContact(
                ACCOUNT, PLATFORM, "uid:HR-1", "2026-11-05 11:00:00");

        assertNotNull(touched);
        assertEquals("2026-11-05 11:00:00", touched.lastContactAt());
        assertEquals(0, touched.version(), "touch 不 bump 版本号");

        assertNull(repository.touchLastContact(ACCOUNT, PLATFORM, "uid:missing", "2026-11-05 11:00:00"));
    }

    @Test
    void listFiltersAndPaginates() {
        repository.insert(state("uid:HR-1", "UNKNOWN", false, false));
        repository.insert(state("uid:HR-2", "REPLIED", false, false));
        repository.insert(state("uid:HR-3", "REJECTED", true, false));
        repository.insert(state("uid:HR-4", "INTERVIEW", false, true));

        PageResult<ContactStateRepository.ContactState> all =
                repository.list(null, null, null, 1, 2);
        assertEquals(4, all.total());
        assertEquals(2, all.items().size());
        assertEquals(2, all.size());
        assertEquals(2, repository.list(null, null, null, 2, 2).items().size());

        assertEquals(1, repository.list("REPLIED", null, null, 1, 20).total());
        assertEquals("uid:HR-2",
                repository.list("REPLIED", null, null, 1, 20).items().get(0).contactKey());
        assertEquals(1, repository.list(null, true, null, 1, 20).total());
        assertEquals(1, repository.list(null, null, true, 1, 20).total());
        assertEquals(4, repository.list("", null, null, 1, 20).total(), "空白筛选不过滤");

        // 分页兜底
        assertEquals(1, repository.list(null, null, null, 0, 0).page());
        assertEquals(20, repository.list(null, null, null, 0, 0).size());
        assertEquals(100, repository.list(null, null, null, 1, 500).size());
        List<ContactStateRepository.ContactState> items =
                repository.list(null, null, null, 0, 0).items();
        assertEquals(4, items.size());
    }

    private ContactStateRepository.ContactState state(String contactKey, String replyState,
                                                      boolean manualTakeover, boolean stopContact) {
        return new ContactStateRepository.ContactState(
                0, ACCOUNT, PLATFORM, contactKey, contactKey.substring("uid:".length()),
                "张HR", "甲公司", replyState, manualTakeover, stopContact, null, null, 0, null, null);
    }
}
