#!/usr/bin/env node
/*
 * Checks group conversations against a real database file.
 *
 * The same checks live in `__tests__/chat-group-conversation.test.js`, and that is
 * where they belong: it is the suite that runs with everything else. This script
 * exists because the suite needs worker processes, and in some environments those
 * are not available - so the migration's most dangerous property, that it keeps
 * every conversation it found, can still be checked directly.
 *
 * Two separate temporary databases are used, and that is deliberate. The migration
 * check has to put the table back the way it was before groups existed, and a
 * database that already contains a group cannot be reshaped that way - the old
 * constraint refuses the row, which is exactly what the check is about.
 *
 * Run: node scripts/verify-group-conversations.js
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const DatabaseManager = require("../database/DatabaseManager");
const ChatConversationService = require("../services/ChatConversationService");

const failures = [];

function check(description, condition, detail = "") {
  const verdict = condition ? "ok  " : "FAIL";
  console.log(`  [${verdict}] ${description}${detail ? ` - ${detail}` : ""}`);
  if (!condition) failures.push(description);
}

function makeTemporaryDatabase(prefix) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  return {
    file: path.join(directory, "childwatch.db"),
    cleanup: () => fs.rmSync(directory, { recursive: true, force: true }),
  };
}

async function registerFamily(db) {
  const parentOne = "verify-parent-0001";
  const parentTwo = "verify-parent-0002";
  const child = "verify-child-0001";

  await db.registerDevice(parentOne, {
    device_name: "Первый",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(parentTwo, {
    device_name: "Второй",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(child, {
    device_name: "Ребёнок",
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentOne,
    childDeviceId: child,
    parentDisplayName: "Первый",
    childDisplayName: "Ребёнок",
    createdBy: "verify-group-conversations",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parentTwo,
    childDeviceId: child,
    parentDisplayName: "Второй",
    childDisplayName: "Ребёнок",
    createdBy: "verify-group-conversations",
  });

  const [family] = await db.getFamiliesForDevice(child);
  const devices = await db.getFamilyDevices(family.id);
  const memberByDevice = new Map(
    devices.map((device) => [device.deviceId, device.memberId])
  );
  return { family, memberByDevice };
}

/** Rebuilds `chat_conversations` the way it looked before groups were allowed. */
async function restoreOldConversationSchema(db) {
  await db.run("PRAGMA foreign_keys = OFF");
  try {
    await db.run(
      "CREATE TABLE chat_conversations_old_shape AS SELECT * FROM chat_conversations"
    );
    await db.run("DROP TABLE chat_conversations");
    await db.run(
      `CREATE TABLE chat_conversations (
         id TEXT PRIMARY KEY,
         family_id TEXT,
         type TEXT NOT NULL CHECK (type IN ('FAMILY', 'DIRECT', 'LEGACY')),
         title TEXT,
         direct_pair_key TEXT,
         created_by_member_id TEXT,
         next_sequence INTEGER NOT NULL DEFAULT 0,
         is_active INTEGER NOT NULL DEFAULT 1,
         created_at INTEGER NOT NULL,
         updated_at INTEGER NOT NULL,
         FOREIGN KEY (family_id) REFERENCES families (id),
         FOREIGN KEY (created_by_member_id) REFERENCES family_members (id)
       )`
    );
    await db.run(
      `INSERT INTO chat_conversations (
         id, family_id, type, title, direct_pair_key, created_by_member_id,
         next_sequence, is_active, created_at, updated_at
       )
       SELECT
         id, family_id, type, title, direct_pair_key, created_by_member_id,
         next_sequence, is_active, created_at, updated_at
       FROM chat_conversations_old_shape`
    );
    await db.run("DROP TABLE chat_conversations_old_shape");
    await db.run(
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_family_conversation ON chat_conversations (family_id) WHERE type = 'FAMILY' AND is_active = 1"
    );
    await db.run(
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_direct_conversation ON chat_conversations (family_id, direct_pair_key) WHERE type = 'DIRECT' AND is_active = 1"
    );
  } finally {
    await db.run("PRAGMA foreign_keys = ON");
  }
  await db.run(
    "DELETE FROM schema_migrations WHERE name = 'chat_group_conversation_type_v1'"
  );
}

async function checkGroupsAndMembership() {
  console.log("\n1. a group holds exactly the members that were chosen");
  const temporary = makeTemporaryDatabase("cw-group-verify-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  try {
    // Only the manager's own startup chatter is muted, never this script's report.
    console.log = quiet;
    await db.initialize();
    console.log = realLog;
    const { family, memberByDevice } = await registerFamily(db);
    const first = memberByDevice.get("verify-parent-0001");
    const second = memberByDevice.get("verify-parent-0002");
    const third = memberByDevice.get("verify-child-0001");

    const group = await db.createGroupConversation({
      familyId: family.id,
      title: "Школа",
      memberIds: [first, second],
      createdByMemberId: first,
    });
    check("the conversation is a GROUP", group.type === "GROUP", group.type);
    check("the name is kept", group.title === "Школа", group.title);
    const members = await db.getGroupConversationMemberIds(group.id);
    check(
      "exactly two members, not the whole family",
      members.length === 2 && members.includes(first) && members.includes(second),
      `${members.length}`
    );
    check(
      "the creator administers it",
      (await db.getGroupAdminMemberId(group.id, first)) === first
    );

    console.log("\n2. refusals");
    let refusedSmall = false;
    try {
      await db.createGroupConversation({
        familyId: family.id,
        title: "Один",
        memberIds: [first],
        createdByMemberId: first,
      });
    } catch (error) {
      refusedSmall = /at least two members/.test(error.message);
    }
    check("a one-person group is refused", refusedSmall);

    let refusedStranger = false;
    try {
      await db.createGroupConversation({
        familyId: family.id,
        title: "Чужие",
        memberIds: [first, "member_other_family"],
        createdByMemberId: first,
      });
    } catch (error) {
      refusedStranger = /must belong to the family/.test(error.message);
    }
    check("a member of another family is refused", refusedStranger);

    const familyConversation = await db.ensureFamilyConversation(family.id);
    let refusedFamily = false;
    try {
      await db.addGroupConversationMembers(familyConversation.id, [third]);
    } catch (error) {
      refusedFamily = /chosen membership/.test(error.message);
    }
    check("the family chat refuses a chosen membership", refusedFamily);

    const direct = await db.createDirectConversation({
      familyId: family.id,
      memberIds: [first, second],
      createdByMemberId: first,
    });
    let refusedDirect = false;
    try {
      await db.addGroupConversationMembers(direct.id, [third]);
    } catch (error) {
      refusedDirect = /chosen membership/.test(error.message);
    }
    check("a direct chat refuses a chosen membership", refusedDirect);

    console.log("\n3. membership can change");
    check(
      "a third member is added",
      (await db.addGroupConversationMembers(group.id, [third])).length === 3
    );
    check(
      "adding twice does not duplicate a seat",
      (await db.addGroupConversationMembers(group.id, [third])).length === 3
    );
    const afterRemoval = await db.removeGroupConversationMember(group.id, second);
    check(
      "one member is taken out",
      afterRemoval.length === 2 && !afterRemoval.includes(second),
      `${afterRemoval.length}`
    );
    check(
      "the administrator is still the creator",
      (await db.getGroupAdminMemberId(group.id, first)) === first
    );
  } finally {
    console.log = realLog;
    await db.close();
    temporary.cleanup();
  }
}

async function checkMigration() {
  console.log("\n4. the migration on a database that already has conversations");
  const temporary = makeTemporaryDatabase("cw-group-migration-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  try {
    console.log = quiet;
    await db.initialize();
    console.log = realLog;
    const { family, memberByDevice } = await registerFamily(db);
    const first = memberByDevice.get("verify-parent-0001");
    const second = memberByDevice.get("verify-parent-0002");
    const familyConversation = await db.ensureFamilyConversation(family.id);

    await restoreOldConversationSchema(db);
    const before = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");

    let oldSchemaRefusedGroup = false;
    try {
      await db.run(
        `INSERT INTO chat_conversations (
           id, family_id, type, title, next_sequence, is_active, created_at, updated_at
         ) VALUES ('chat_conversation_probe', ?, 'GROUP', 'Проба', 0, 1, 1, 1)`,
        [family.id]
      );
    } catch (error) {
      oldSchemaRefusedGroup = /CHECK constraint failed/.test(error.message);
    }
    check(
      "the old schema really refuses a GROUP, so the migration is not decoration",
      oldSchemaRefusedGroup
    );

    console.log = quiet;
    let result;
    let migrationError = null;
    try {
      result = await db.ensureChatConversationGroupType();
    } catch (error) {
      migrationError = error;
    }
    console.log = realLog;
    check(
      "the migration completed",
      !migrationError,
      migrationError ? migrationError.message : ""
    );
    if (migrationError) return;

    check(
      "it moved every conversation",
      result.movedRows === Number(before.total),
      `${result.movedRows} of ${before.total}`
    );
    const after = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    check("no conversation was lost", Number(after.total) === Number(before.total));
    const restored = await db.getChatConversationById(familyConversation.id);
    check("the family conversation is still readable", restored?.type === "FAMILY");
    check(
      "its membership still resolves",
      Boolean(await db.getChatConversationForMember(familyConversation.id, first))
    );
    check(
      "no broken references were left behind",
      (await db.all("PRAGMA foreign_key_check")).length === 0
    );

    const group = await db.createGroupConversation({
      familyId: family.id,
      title: "После обновления",
      memberIds: [first, second],
      createdByMemberId: first,
    });
    check("a group can be created afterwards", group.type === "GROUP");

    console.log = quiet;
    const secondRun = await db.ensureChatConversationGroupType();
    console.log = realLog;
    check(
      "running the migration again does nothing",
      secondRun.skipped === "already applied",
      secondRun.skipped || ""
    );

    const finalCount = await db.get("SELECT COUNT(*) AS total FROM chat_conversations");
    check(
      "the final count is the original plus the new group",
      Number(finalCount.total) === Number(before.total) + 1,
      `${finalCount.total}`
    );
  } finally {
    console.log = realLog;
    await db.close();
    temporary.cleanup();
  }
}

async function checkGroupService() {
  console.log("\n5. the service: creating, managing and leaving a group");
  const temporary = makeTemporaryDatabase("cw-group-service-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  try {
    console.log = quiet;
    await db.initialize();
    console.log = realLog;

    const { memberByDevice } = await registerFamily(db);
    const service = new ChatConversationService(db);
    const [parentOne, parentTwo, child] = [
      "verify-parent-0001",
      "verify-parent-0002",
      "verify-child-0001",
    ];
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);
    const third = memberByDevice.get(child);

    const created = await service.createGroup(parentOne, {
      title: "Кружок",
      memberIds: [second],
    });
    const groupId = created.conversation.conversationId;
    check(
      "the group is created with the caller and the person chosen",
      created.created === true && created.conversation.members.length === 2,
      `${created.conversation.members.length} member(s)`
    );
    check(
      "it carries its own name, not the family's",
      created.conversation.title === "Кружок",
      created.conversation.title
    );
    check(
      "the caller administers it",
      created.conversation.canManageGroup === true,
      String(created.conversation.adminMemberId)
    );

    let refusedAdd = false;
    try {
      await service.addGroupMembers(parentTwo, groupId, { memberIds: [third] });
    } catch (error) {
      refusedAdd = error.code === "GROUP_ADMIN_REQUIRED";
    }
    check("only the administrator adds people", refusedAdd);

    let refusedRemove = false;
    try {
      await service.removeGroupMember(parentTwo, groupId, first);
    } catch (error) {
      refusedRemove = error.code === "GROUP_ADMIN_REQUIRED";
    }
    check("only the administrator removes people", refusedRemove);

    const added = await service.addGroupMembers(parentOne, groupId, {
      memberIds: [third],
    });
    check(
      "the administrator adds a third member",
      added.members.length === 3,
      `${added.members.length}`
    );

    let refusedSelf = false;
    try {
      await service.removeGroupMember(parentOne, groupId, first);
    } catch (error) {
      refusedSelf = error.code === "GROUP_ADMIN_CANNOT_REMOVE_SELF";
    }
    check(
      "the administrator is told to leave rather than remove themselves",
      refusedSelf
    );

    const removed = await service.removeGroupMember(parentOne, groupId, second);
    check(
      "the administrator removes a member",
      removed.members.length === 2 &&
        !removed.members.some((member) => member.memberId === second),
      `${removed.members.length} left`
    );

    let refusedRead = false;
    try {
      await service.getMessages(parentTwo, groupId, {});
    } catch (error) {
      refusedRead = error.code === "CONVERSATION_ACCESS_DENIED";
    }
    check("somebody taken out can no longer read the group", refusedRead);

    let leftOwnGroup = false;
    try {
      await service.leaveGroup(child, groupId);
      leftOwnGroup = true;
    } catch (error) {
      leftOwnGroup = false;
    }
    check("a member leaves a group they are in", leftOwnGroup);

    // One person is left, so the group is over: it must stop being listed.
    const afterLastLeave = await service.listConversations(parentOne);
    check(
      "a group with one person left is closed and disappears from the list",
      !afterLastLeave.conversations.some(
        (conversation) => conversation.conversationId === groupId
      )
    );

    // A fresh group, where the administrator wants out: the way out is to hand the
    // group over or to close it, never to walk away from it.
    const secondGroup = await service.createGroup(parentOne, {
      title: "Дача",
      memberIds: [second, third],
    });
    const handoverId = secondGroup.conversation.conversationId;

    let refusedAdminLeave = false;
    try {
      await service.leaveGroup(parentOne, handoverId);
    } catch (error) {
      refusedAdminLeave = error.code === "GROUP_ADMIN_CANNOT_LEAVE";
    }
    check("the administrator cannot leave", refusedAdminLeave);

    let refusedStrangerHandover = false;
    try {
      await service.transferGroupAdmin(parentOne, handoverId, "member_other");
    } catch (error) {
      refusedStrangerHandover = error.code === "GROUP_MEMBER_NOT_FOUND";
    }
    check(
      "administration is not handed to somebody outside the group",
      refusedStrangerHandover
    );

    let refusedSelfHandover = false;
    try {
      await service.transferGroupAdmin(parentOne, handoverId, first);
    } catch (error) {
      refusedSelfHandover = error.code === "GROUP_ADMIN_ALREADY";
    }
    check(
      "administration is not handed to the current administrator",
      refusedSelfHandover
    );

    let refusedMemberHandover = false;
    try {
      await service.transferGroupAdmin(parentTwo, handoverId, third);
    } catch (error) {
      refusedMemberHandover = error.code === "GROUP_ADMIN_REQUIRED";
    }
    check("only the administrator hands the group over", refusedMemberHandover);

    const handover = await service.transferGroupAdmin(
      parentOne,
      handoverId,
      second
    );
    check(
      "administration is handed to the chosen member",
      handover.adminMemberId === second,
      String(handover.adminMemberId)
    );
    // The settings are always answered for the caller, so the new administrator has
    // to be asked, not the person who handed the group over.
    const newAdminSettings = await service.getGroupSettings(
      parentTwo,
      handoverId
    );
    check(
      "the new administrator may manage the group",
      newAdminSettings.canManage === true
    );
    const formerAdminSettings = await service.getGroupSettings(
      parentOne,
      handoverId
    );
    check(
      "the former administrator may no longer manage it",
      formerAdminSettings.canManage === false
    );

    const formerAdminLeft = await service.leaveGroup(parentOne, handoverId);
    check(
      "a member who handed the group over can leave",
      formerAdminLeft.left === true && formerAdminLeft.remainingMembers === 2,
      `${formerAdminLeft.remainingMembers} left`
    );

    const closed = await service.closeGroup(parentTwo, handoverId);
    check("the administrator closes the group", closed.closed === true);
    const afterClose = await service.listConversations(parentTwo);
    check(
      "a closed group is gone from the list",
      !afterClose.conversations.some(
        (conversation) => conversation.conversationId === handoverId
      )
    );

    let refusedWriteAfterClose = false;
    try {
      await service.sendMessage(parentTwo, handoverId, {
        clientMessageId: "group-after-close-0001",
        text: "Кто-нибудь здесь?",
      });
    } catch (error) {
      refusedWriteAfterClose =
        error.code === "CONVERSATION_NOT_FOUND" ||
        error.code === "CONVERSATION_ACCESS_DENIED";
    }
    check("nobody writes in a closed group", refusedWriteAfterClose);

    let refusedDirection = false;
    const direct = await service.createDirectConversation(parentOne, second);
    try {
      await service.leaveGroup(parentOne, direct.conversation.conversationId);
    } catch (error) {
      refusedDirection = error.code === "NOT_A_GROUP_CONVERSATION";
    }
    check("a personal conversation is not 'left'", refusedDirection);

    let refusedStrangerGroup = false;
    try {
      await service.createGroup(parentOne, {
        title: "Чужие",
        memberIds: ["member_other_family"],
      });
    } catch (error) {
      refusedStrangerGroup = error.code === "GROUP_TARGET_NOT_AVAILABLE";
    }
    check("a group cannot be made with somebody outside the family", refusedStrangerGroup);

    let refusedNamelessGroup = false;
    try {
      await service.createGroup(parentOne, { title: "   ", memberIds: [second] });
    } catch (error) {
      refusedNamelessGroup = error.code === "GROUP_TITLE_REQUIRED";
    }
    check("a nameless group is refused", refusedNamelessGroup);

    let refusedAlone = false;
    try {
      await service.createGroup(parentOne, { title: "Один", memberIds: [] });
    } catch (error) {
      refusedAlone = error.code === "GROUP_MEMBERS_REQUIRED";
    }
    check("a group of one person is refused", refusedAlone);
  } finally {
    console.log = realLog;
    await db.close();
    temporary.cleanup();
  }
}

async function checkGroupRoutes() {
  console.log("\n6. the routes: the same rules over HTTP");
  if (typeof fetch !== "function") {
    check("this Node has fetch, which this section needs", false, process.version);
    return;
  }

  const temporary = makeTemporaryDatabase("cw-group-routes-");
  const db = new DatabaseManager(temporary.file);
  const realLog = console.log;
  const quiet = () => {};
  let server = null;
  try {
    console.log = quiet;
    await db.initialize();
    console.log = realLog;

    const { memberByDevice } = await registerFamily(db);
    const service = new ChatConversationService(db);
    const express = require("express");
    const createChatV2Routes = require("../routes/chat-v2");

    const parentOne = "verify-parent-0001";
    const parentTwo = "verify-parent-0002";
    const child = "verify-child-0001";
    const first = memberByDevice.get(parentOne);
    const second = memberByDevice.get(parentTwo);
    const third = memberByDevice.get(child);

    const app = express();
    app.use(express.json());
    // The device is normally put on the request by the authentication layer; here it
    // comes from a header, so the routes can be exercised without tokens.
    app.use((req, _res, next) => {
      req.deviceId = req.header("x-test-device") || null;
      next();
    });
    app.use("/api/chat", createChatV2Routes(db, service));

    server = await new Promise((resolve) => {
      const listening = app.listen(0, () => resolve(listening));
    });
    const base = `http://127.0.0.1:${server.address().port}/api/chat`;

    const call = async (method, path, { device = parentOne, body = null } = {}) => {
      const headers = { "content-type": "application/json" };
      if (device) headers["x-test-device"] = device;
      const response = await fetch(`${base}${path}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      });
      let payload = null;
      try {
        payload = await response.json();
      } catch (error) {
        payload = null;
      }
      return { status: response.status, payload };
    };

    const anonymous = await call("GET", "/conversations", { device: "" });
    check(
      "a request without a device is refused",
      anonymous.status === 401 &&
        anonymous.payload?.code === "AUTHENTICATED_DEVICE_REQUIRED",
      `${anonymous.status} ${anonymous.payload?.code || ""}`
    );

    const created = await call("POST", "/conversations/group", {
      body: { title: "Поход", memberIds: [second] },
    });
    const groupId = created.payload?.conversation?.conversationId;
    check(
      "a group is created over HTTP",
      created.status === 201 && created.payload?.conversation?.type === "GROUP",
      `${created.status} ${created.payload?.conversation?.type || ""}`
    );

    const listed = await call("GET", "/conversations");
    check(
      "it appears in the conversation list",
      listed.status === 200 &&
        listed.payload?.conversations?.some(
          (conversation) => conversation.conversationId === groupId
        )
    );

    const added = await call("POST", `/conversations/${groupId}/group/members`, {
      body: { memberIds: [third] },
    });
    check(
      "the administrator adds a member over HTTP",
      added.status === 200 && added.payload?.members?.length === 3,
      `${added.status} ${added.payload?.members?.length ?? "?"}`
    );

    const removed = await call(
      "DELETE",
      `/conversations/${groupId}/group/members/${third}`
    );
    check(
      "the administrator removes a member over HTTP",
      removed.status === 200 && removed.payload?.members?.length === 2,
      `${removed.status}`
    );

    const adminLeft = await call("POST", `/conversations/${groupId}/group/leave`);
    check(
      "the administrator is refused when leaving",
      adminLeft.status === 409 &&
        adminLeft.payload?.code === "GROUP_ADMIN_CANNOT_LEAVE",
      `${adminLeft.status} ${adminLeft.payload?.code || ""}`
    );

    // Three people in the group again, so the leave below does not end it.
    const restored = await call("POST", `/conversations/${groupId}/group/members`, {
      body: { memberIds: [third] },
    });
    check(
      "the removed member can be added back",
      restored.status === 200 && restored.payload?.members?.length === 3,
      `${restored.status}`
    );

    const handover = await call("POST", `/conversations/${groupId}/group/admin`, {
      body: { memberId: second },
    });
    check(
      "administration is handed over over HTTP",
      handover.status === 200 && handover.payload?.adminMemberId === second,
      `${handover.status} ${handover.payload?.adminMemberId || ""}`
    );

    const oldAdminLeave = await call("POST", `/conversations/${groupId}/group/leave`);
    check(
      "the former administrator may leave once the group is handed over",
      oldAdminLeave.status === 200 && oldAdminLeave.payload?.left === true,
      `${oldAdminLeave.status}`
    );

    const closed = await call("DELETE", `/conversations/${groupId}/group`, {
      device: parentTwo,
    });
    check(
      "the new administrator closes the group over HTTP",
      closed.status === 200 && closed.payload?.closed === true,
      `${closed.status}`
    );

    const afterClose = await call("GET", "/conversations", { device: parentTwo });
    check(
      "a closed group is gone from the list over HTTP",
      afterClose.status === 200 &&
        !afterClose.payload?.conversations?.some(
          (conversation) => conversation.conversationId === groupId
        )
    );

    const writeAfterClose = await call(
      "POST",
      `/conversations/${groupId}/messages`,
      { device: parentTwo, body: { clientMessageId: "after-close-0001", text: "?" } }
    );
    check(
      "nobody writes in a closed group over HTTP",
      writeAfterClose.status === 404 || writeAfterClose.status === 403,
      `${writeAfterClose.status} ${writeAfterClose.payload?.code || ""}`
    );
  } finally {
    console.log = realLog;
    if (server) {
      await new Promise((resolve) => server.close(() => resolve()));
    }
    await db.close();
    temporary.cleanup();
  }
}

async function main() {
  // Each check mutes the manager's own startup chatter around itself; nothing here
  // may mute the report.
  await checkGroupsAndMembership();
  await checkGroupService();
  await checkGroupRoutes();
  await checkMigration();

  console.log("");
  if (failures.length) {
    console.log(`FAILED: ${failures.length} check(s)`);
    failures.forEach((failure) => console.log(`  - ${failure}`));
    process.exitCode = 1;
    return;
  }
  console.log("All checks passed.");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
