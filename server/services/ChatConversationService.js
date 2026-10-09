const AvatarPresetCatalog = require("./AvatarPresetCatalog");
const MAX_CHAT_TEXT_BYTES = 16 * 1024;
const MAX_CLIENT_MESSAGE_ID_LENGTH = 200;
const MAX_IDENTIFIER_LENGTH = 200;
const DEFAULT_PAGE_LIMIT = 50;
const MAX_PAGE_LIMIT = 200;

/** Shared name of a group chat, kept short enough for the chat list. */
const MAX_GROUP_TITLE_LENGTH = 64;

/**
 * How long after sending the author may still rewrite a message.
 *
 * Editing is meant to fix a mistake, not to change history the others have
 * already read, so the window is deliberately short.
 */
const EDIT_WINDOW_MS = 30 * 60 * 1000;

class ChatConversationError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "ChatConversationError";
    this.status = status;
    this.code = code;
  }
}

class ChatConversationService {
  constructor(dbManager, options = {}) {
    if (!dbManager) {
      throw new Error("ChatConversationService requires a database manager");
    }
    this.dbManager = dbManager;
    this.attachments = new (require("./ChatAttachmentStore"))(dbManager, options.attachments || {});
    this.transcriptions = new (require("./ChatTranscriptionService"))(this, options.transcriptions || {});
  }

  requireDeviceId(deviceId) {
    if (typeof deviceId !== "string" || !deviceId.trim()) {
      throw new ChatConversationError(
        401,
        "AUTHENTICATED_DEVICE_REQUIRED",
        "Authenticated device is required"
      );
    }
    return deviceId.trim();
  }

  requireIdentifier(value, { code, message }) {
    if (typeof value !== "string") {
      throw new ChatConversationError(400, code, message);
    }
    const normalized = value.trim();
    if (!normalized || normalized.length > MAX_IDENTIFIER_LENGTH) {
      throw new ChatConversationError(400, code, message);
    }
    return normalized;
  }

  parsePositiveInteger(value, { code, message, maximum = null }) {
    let parsed;
    if (typeof value === "number") {
      parsed = value;
    } else if (typeof value === "string" && /^[1-9]\d*$/.test(value)) {
      parsed = Number(value);
    } else {
      throw new ChatConversationError(400, code, message);
    }

    if (
      !Number.isSafeInteger(parsed) ||
      parsed <= 0 ||
      (maximum !== null && parsed > maximum)
    ) {
      throw new ChatConversationError(400, code, message);
    }
    return parsed;
  }

  parseReceiptSequence(value) {
    if (!Number.isSafeInteger(value) || value < 0) {
      throw new ChatConversationError(
        400,
        "INVALID_RECEIPT_SEQUENCE",
        "Receipt sequences must be non-negative integers"
      );
    }
    return value;
  }

  async resolveDeviceMemberships(deviceId) {
    const normalizedDeviceId = this.requireDeviceId(deviceId);
    const families = await this.dbManager.getFamiliesForDevice(
      normalizedDeviceId
    );
    const memberships = [];
    const seen = new Set();

    for (const family of families || []) {
      const familyId = String(family?.id || "").trim();
      if (!familyId) continue;
      const membership = await this.dbManager.getFamilyDeviceMembership(
        familyId,
        normalizedDeviceId
      );
      const memberId = String(membership?.memberId || "").trim();
      if (!membership || !memberId || membership.familyId !== familyId) {
        continue;
      }
      const key = `${familyId}\u0000${memberId}`;
      if (seen.has(key)) continue;
      seen.add(key);
      memberships.push({
        familyId,
        memberId,
        memberRole: membership.memberRole || null,
        deviceId: normalizedDeviceId,
      });
    }

    if (!memberships.length) {
      throw new ChatConversationError(
        403,
        "CHAT_MEMBERSHIP_REQUIRED",
        "Device is not an active family member"
      );
    }
    return memberships;
  }

  async resolveConversationActor(deviceId, conversationId) {
    const normalizedConversationId = this.requireIdentifier(conversationId, {
      code: "INVALID_CONVERSATION_ID",
      message: "Invalid conversation id",
    });
    const memberships = await this.resolveDeviceMemberships(deviceId);
    const conversation = await this.dbManager.getChatConversationById(
      normalizedConversationId
    );
    if (!conversation || conversation.isActive !== 1) {
      throw new ChatConversationError(
        404,
        "CONVERSATION_NOT_FOUND",
        "Conversation not found"
      );
    }

    for (const membership of memberships) {
      if (membership.familyId !== conversation.familyId) continue;
      const scopedConversation =
        await this.dbManager.getChatConversationForMember(
          normalizedConversationId,
          membership.memberId
        );
      if (scopedConversation) {
        return {
          ...membership,
          conversation: scopedConversation,
        };
      }
    }

    throw new ChatConversationError(
      403,
      "CONVERSATION_ACCESS_DENIED",
      "Device is not a participant in this conversation"
    );
  }

  formatMember(member) {
    if (!member) return null;
    return {
      memberId: member.id || member.memberId,
      displayName: member.displayName || "Участник",
      role: member.role || null,
      avatarKey: member.avatarKey || null,
    };
  }

  /**
   * Who is in a conversation.
   *
   * The family chat holds the whole family and a direct chat holds two people, so
   * both are derived from something else. A group holds the people somebody picked,
   * and that list is read from the conversation itself: deriving it from the family
   * would silently add a new family member to somebody's private group, and silently
   * remove one who left the family.
   */
  async conversationMembers(conversation, familyMembers = []) {
    if (!conversation) return [];
    const activeMembers = (familyMembers || [])
      .map((member) => this.formatMember(member))
      .filter((member) => member?.memberId);

    if (conversation.type === "GROUP") {
      const groupMemberIds = new Set(
        await this.dbManager.getGroupConversationMemberIds(conversation.id)
      );
      return activeMembers.filter((member) => groupMemberIds.has(member.memberId));
    }

    if (conversation.type !== "DIRECT") return activeMembers;

    const directMemberIds = new Set(
      String(conversation.directPairKey || "")
        .split("|")
        .map((memberId) => memberId.trim())
        .filter(Boolean)
    );
    return activeMembers.filter((member) => directMemberIds.has(member.memberId));
  }

  /**
   * Builds the conversation DTO.
   *
   * A group conversation is shared, so its name and picture come from the family
   * and are the same for everyone; a direct conversation has no shared settings.
   * [adminMemberId] tells the clients who may change the shared values.
   */
  /**
   * Who may change the shared settings of a family group chat.
   *
   * The creator is preferred. When that is unknown, the longest-standing active
   * member takes over, so a group is never left without an administrator.
   */
  async resolveFamilyAdmin(familyId, familyConversation = null) {
    if (!familyId) return null;
    const preferred =
      familyConversation?.createdByMemberId ||
      familyConversation?.created_by_member_id ||
      null;
    return this.dbManager.getFamilyAdminMemberId(familyId, preferred);
  }

  /**
   * Who administers a conversation.
   *
   * The family chat is administered by the family's administrator; a group by the
   * person who created it, and — when that person has left — by the longest-standing
   * remaining member, so a group is never left without one.
   */
  async resolveGroupAdmin(conversation) {
    if (!conversation || conversation.type === "DIRECT") return null;
    if (conversation.type === "GROUP") {
      return this.dbManager.getGroupAdminMemberId(
        conversation.id,
        conversation.createdByMemberId || conversation.created_by_member_id || null
      );
    }
    return this.resolveFamilyAdmin(conversation.familyId, conversation);
  }

  /** Group settings plus whether the caller may change them. */
  async getGroupSettings(deviceId, conversationId) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    if (actor.conversation.type === "DIRECT") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "A direct conversation has no shared group settings"
      );
    }
    const isFamilyConversation = actor.conversation.type === "FAMILY";
    const family = isFamilyConversation
      ? await this.dbManager.getFamilyById(actor.conversation.familyId)
      : null;
    const adminMemberId = await this.resolveGroupAdmin(actor.conversation);
    const familyMembers = await this.dbManager.getChatFamilyMembers(
      actor.conversation.familyId
    );
    return {
      conversationId: actor.conversation.id,
      familyId: actor.conversation.familyId,
      type: actor.conversation.type,
      // A family chat takes its name and picture from the family, because they are
      // shared with the rest of the application. A group has its own name and no
      // picture of its own yet.
      title: isFamilyConversation
        ? family?.name || actor.conversation.title || null
        : actor.conversation.title || null,
      avatarKey: isFamilyConversation ? family?.avatarKey || null : null,
      adminMemberId,
      canManage: Boolean(adminMemberId && adminMemberId === actor.memberId),
      actorMemberId: actor.memberId,
      members: await this.conversationMembers(actor.conversation, familyMembers),
    };
  }

  /**
   * Renames the group chat for every participant.
   *
   * Only the administrator may do this, and the value is stored on the family so
   * all members read the same name.
   */
  async updateGroupTitle(deviceId, conversationId, payload) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    const title = this.validateGroupTitle(payload?.title);
    // A family chat keeps its name on the family, where the rest of the application
    // reads it; a group keeps its own, because two groups in one family may have
    // different names and neither of them is the family's.
    if (actor.conversation.type === "GROUP") {
      await this.dbManager.updateGroupConversationTitle(
        actor.conversation.id,
        title
      );
    } else {
      await this.dbManager.updateFamilyGroupName(actor.conversation.familyId, title);
    }
    return this.getGroupSettings(deviceId, conversationId);
  }

  /**
   * Sets the shared picture of a conversation. Only the administrator may.
   *
   * A family chat keeps its picture on the family, where the rest of the application
   * reads it. A group has no picture of its own yet, and this is refused rather than
   * quietly written to the family: the family's picture is shown on the family chat,
   * in the family list and everywhere else, so a group's administrator changing
   * "the group picture" would have changed the picture of the whole family.
   */
  async updateGroupAvatar(deviceId, conversationId, payload) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    if (actor.conversation.type !== "FAMILY") {
      throw new ChatConversationError(
        400,
        "GROUP_AVATAR_UNSUPPORTED",
        "A group has no picture of its own yet"
      );
    }
    const raw = payload?.avatarKey;
    const avatarKey = typeof raw === "string" ? raw.trim() : "";
    if (avatarKey && !AvatarPresetCatalog.isValidAvatarKey(avatarKey)) {
      throw new ChatConversationError(
        400,
        "UNSUPPORTED_GROUP_AVATAR",
        "Unsupported group avatar"
      );
    }
    await this.dbManager.updateFamilyGroupAvatar(
      actor.conversation.familyId,
      avatarKey || null
    );
    return this.getGroupSettings(deviceId, conversationId);
  }

  /** Loads the conversation and asserts that the caller administers its group. */
  async requireGroupAdmin(deviceId, conversationId) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    if (actor.conversation.type === "DIRECT") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "A direct conversation has no shared group settings"
      );
    }
    const adminMemberId = await this.resolveGroupAdmin(actor.conversation);
    if (!adminMemberId || adminMemberId !== actor.memberId) {
      throw new ChatConversationError(
        403,
        "GROUP_ADMIN_REQUIRED",
        "Only the group administrator can change this"
      );
    }
    return actor;
  }

  formatConversation(
    conversation,
    { members = [], actorMemberId = null, family = null, adminMemberId = null } = {}
  ) {
    if (!conversation) return null;
    const otherMembers = members.filter(
      (member) => member.memberId !== actorMemberId
    );
    const isGroup = conversation.type !== "DIRECT";
    const isFamilyConversation = conversation.type === "FAMILY";
    // The family name is what everyone sees, so a group rename by the admin
    // reaches all participants through this value. A group of one's own keeps its
    // own name, which is not the family's.
    const sharedTitle =
      isFamilyConversation && family?.name ? String(family.name).trim() : "";
    const resolvedTitle =
      sharedTitle ||
      conversation.title ||
      (conversation.type === "DIRECT"
        ? otherMembers.map((member) => member.displayName).join(", ") ||
          "Личный чат"
        : "Семейный чат");
    return {
      conversationId: conversation.id,
      familyId: conversation.familyId,
      type: conversation.type,
      title: resolvedTitle,
      avatarKey: isFamilyConversation ? family?.avatarKey || null : null,
      adminMemberId: isGroup ? adminMemberId || null : null,
      canManageGroup: Boolean(
        isGroup && adminMemberId && actorMemberId && adminMemberId === actorMemberId
      ),
      actorMemberId,
      members,
      lastSequence: Number(conversation.nextSequence) || 0,
      lastDeliveredSequence:
        Number(conversation.lastDeliveredSequence) || 0,
      lastReadSequence: Number(conversation.lastReadSequence) || 0,
      mutedUntil: conversation.mutedUntil || null,
      lastMessagePreview: conversation.lastMessageText || null,
      unreadCount: Number(conversation.unreadCount) || 0,
      updatedAt: conversation.updatedAt || null,
    };
  }

  formatMessage(message) {
    if (!message) return null;
    // A withdrawn message keeps its place but loses its text, so clients can
    // render "message deleted" instead of the content.
    const withdrawn = Boolean(message.deletedAt);
    return {
      messageId: message.id,
      clientMessageId: message.clientMessageId,
      conversationId: message.conversationId,
      serverSequence: message.sequence,
      senderMemberId: message.senderMemberId || null,
      senderDisplayName: message.senderDisplayName,
      senderRole: message.senderRoleSnapshot || null,
      text: withdrawn ? "" : message.text,
      editedAt: message.editedAt || null,
      deletedAt: message.deletedAt || null,
      clientSentAt: message.clientSentAt || message.serverCreatedAt,
      serverCreatedAt: message.serverCreatedAt,
      legacyMessageId:
        message.legacyMessageId === null ||
        message.legacyMessageId === undefined
          ? null
          : String(message.legacyMessageId),
    };
  }

  async listConversations(deviceId) {
    const memberships = await this.resolveDeviceMemberships(deviceId);
    const byId = new Map();

    for (const membership of memberships) {
      const familyMembers = await this.dbManager.getChatFamilyMembers(
        membership.familyId
      );
      // The permanent family conversation is lazy-created for installations
      // upgraded from a schema that predates chat v2.
      const expectedFamilyConversationId =
        this.dbManager.createFamilyConversationId(membership.familyId);
      let familyConversation = await this.dbManager.getChatConversationById(
        expectedFamilyConversationId
      );
      let scopedFamilyConversation = familyConversation
        ? await this.dbManager.getChatConversationForMember(
            expectedFamilyConversationId,
            membership.memberId
          )
        : null;
      // Normal conversation-list reads must remain read-only. Reconciliation
      // takes a SQLite write lock and used to delay every screen opening by
      // several seconds on long-lived databases. Only repair genuinely
      // missing conversation/membership rows.
      if (!familyConversation || !scopedFamilyConversation) {
        familyConversation = await this.dbManager.ensureFamilyConversation(
          membership.familyId
        );
        scopedFamilyConversation =
          await this.dbManager.getChatConversationForMember(
            expectedFamilyConversationId,
            membership.memberId
          );
      }
      if (familyConversation?.id !== expectedFamilyConversationId) {
        throw new Error("Family conversation identity mismatch");
      }
      if (!scopedFamilyConversation) {
        throw new Error("Family conversation membership is missing");
      }
      const conversations =
        await this.dbManager.listChatConversationsForMember(
          membership.memberId,
          MAX_PAGE_LIMIT
        );
      // The family's administrator is looked up once per family; a group has its
      // own, which is not the family's.
      const family = await this.dbManager.getFamilyById(membership.familyId);
      const familyAdminMemberId = await this.resolveFamilyAdmin(
        membership.familyId,
        familyConversation
      );
      for (const conversation of conversations || []) {
        if (conversation.familyId !== membership.familyId) continue;
        const members = await this.conversationMembers(
          conversation,
          familyMembers
        );
        const adminMemberId =
          conversation.type === "GROUP"
            ? await this.dbManager.getGroupAdminMemberId(
                conversation.id,
                conversation.createdByMemberId || null
              )
            : familyAdminMemberId;
        byId.set(
          conversation.id,
          this.formatConversation(conversation, {
            members,
            actorMemberId: membership.memberId,
            family,
            adminMemberId,
          })
        );
      }
    }

    const conversations = Array.from(byId.values()).sort((left, right) => {
      const updatedDifference =
        Number(right.updatedAt || 0) - Number(left.updatedAt || 0);
      return (
        updatedDifference ||
        String(left.conversationId).localeCompare(String(right.conversationId))
      );
    });
    return { conversations };
  }

  async createDirectConversation(deviceId, targetMemberId) {
    const target = this.requireIdentifier(targetMemberId, {
      code: "INVALID_TARGET_MEMBER_ID",
      message: "Invalid target member id",
    });
    const memberships = await this.resolveDeviceMemberships(deviceId);
    if (memberships.some((membership) => membership.memberId === target)) {
      throw new ChatConversationError(
        400,
        "DIRECT_SELF_NOT_ALLOWED",
        "A direct conversation requires another family member"
      );
    }

    for (const membership of memberships) {
      const memberIds = [membership.memberId, target];
      const familyMembers = await this.dbManager.getChatFamilyMembers(
        membership.familyId
      );
      const targetMember = (familyMembers || []).find(
        (member) => member.id === target
      );
      if (!targetMember) continue;
      // These helpers canonicalize the unordered pair and let the HTTP layer
      // report whether this idempotent operation created a new row.
      this.dbManager.createDirectConversationKey(memberIds);
      const conversationId = this.dbManager.createDirectConversationId(
        membership.familyId,
        memberIds
      );
      const existing = await this.dbManager.getChatConversationById(
        conversationId
      );

      try {
        const conversation = await this.dbManager.createDirectConversation({
          familyId: membership.familyId,
          memberIds,
          createdByMemberId: membership.memberId,
        });
        const scopedConversation =
          await this.dbManager.getChatConversationForMember(
            conversation.id,
            membership.memberId
          );
        if (!scopedConversation) {
          throw new Error("Created direct conversation is not member-scoped");
        }
        const members = await this.conversationMembers(
          scopedConversation,
          familyMembers
        );
        return {
          created: !existing || existing.isActive !== 1,
          conversation: this.formatConversation(scopedConversation, {
            members,
            actorMemberId: membership.memberId,
          }),
        };
      } catch (error) {
        if (
          error?.message ===
          "Direct conversation members must belong to the family"
        ) {
          continue;
        }
        throw error;
      }
    }

    throw new ChatConversationError(
      403,
      "DIRECT_TARGET_NOT_AVAILABLE",
      "Target member is not available for a direct conversation"
    );
  }

  /** A group name, checked the same way whether it is chosen or changed. */
  validateGroupTitle(rawTitle) {
    const title = typeof rawTitle === "string" ? rawTitle.trim() : "";
    if (!title) {
      throw new ChatConversationError(
        400,
        "GROUP_TITLE_REQUIRED",
        "Group title must not be empty"
      );
    }
    if (title.length > MAX_GROUP_TITLE_LENGTH) {
      throw new ChatConversationError(
        400,
        "GROUP_TITLE_TOO_LONG",
        `Group title must be at most ${MAX_GROUP_TITLE_LENGTH} characters`
      );
    }
    return title;
  }

  /**
   * Resolves the people a group is being made of, inside the caller's own family.
   *
   * Only members of the family are accepted: a group is a conversation inside one
   * family, and somebody outside it has neither the right to read it nor a way to
   * be reached by it.
   */
  async resolveGroupTargets(familyId, memberIds, { requireAll = true } = {}) {
    const requested = Array.from(
      new Set(
        (memberIds || []).map((id) => String(id || "").trim()).filter(Boolean)
      )
    );
    const familyMembers = await this.dbManager.getChatFamilyMembers(familyId);
    const available = new Set(
      (familyMembers || []).map((member) => String(member.id || ""))
    );
    const unknown = requested.filter((memberId) => !available.has(memberId));
    if (unknown.length && requireAll) {
      throw new ChatConversationError(
        403,
        "GROUP_TARGET_NOT_AVAILABLE",
        "Everybody in a group must be a member of the family"
      );
    }
    return {
      accepted: requested.filter((memberId) => available.has(memberId)),
      unknown,
      familyMembers,
    };
  }

  /**
   * Creates a group with a chosen name and membership.
   *
   * The caller is always in the group and becomes its administrator: a group made on
   * somebody's behalf, which they do not belong to, is not something a person can
   * mean. The name is required, because a nameless group is indistinguishable from
   * every other nameless group in the list.
   */
  async createGroup(deviceId, payload) {
    const title = this.validateGroupTitle(payload?.title);
    const memberships = await this.resolveDeviceMemberships(deviceId);
    let lastRefusal = null;

    for (const membership of memberships) {
      const { accepted, unknown } = await this.resolveGroupTargets(
        membership.familyId,
        payload?.memberIds,
        { requireAll: false }
      );
      const wanted = Array.from(new Set([membership.memberId, ...accepted]));
      if (unknown.length) {
        lastRefusal = new ChatConversationError(
          403,
          "GROUP_TARGET_NOT_AVAILABLE",
          "Everybody in a group must be a member of the family"
        );
        continue;
      }
      if (wanted.length < 2) {
        throw new ChatConversationError(
          400,
          "GROUP_MEMBERS_REQUIRED",
          "A group needs at least one other member"
        );
      }

      const conversation = await this.dbManager.createGroupConversation({
        familyId: membership.familyId,
        title,
        memberIds: wanted,
        createdByMemberId: membership.memberId,
      });
      const familyMembers = await this.dbManager.getChatFamilyMembers(
        membership.familyId
      );
      const scoped = await this.dbManager.getChatConversationForMember(
        conversation.id,
        membership.memberId
      );
      return {
        created: true,
        conversation: this.formatConversation(scoped || conversation, {
          members: await this.conversationMembers(
            scoped || conversation,
            familyMembers
          ),
          actorMemberId: membership.memberId,
          family: null,
          adminMemberId: membership.memberId,
        }),
      };
    }

    throw (
      lastRefusal ||
      new ChatConversationError(
        403,
        "GROUP_TARGET_NOT_AVAILABLE",
        "Nobody in the request shares a family with this device"
      )
    );
  }

  /** Adds people to a group. Only the administrator may. */
  async addGroupMembers(deviceId, conversationId, payload) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    if (actor.conversation.type !== "GROUP") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "Only a group has a membership that can be changed"
      );
    }
    const { accepted } = await this.resolveGroupTargets(
      actor.conversation.familyId,
      payload?.memberIds
    );
    if (!accepted.length) {
      throw new ChatConversationError(
        400,
        "GROUP_MEMBERS_REQUIRED",
        "No family member was named to add"
      );
    }
    await this.dbManager.addGroupConversationMembers(
      actor.conversation.id,
      accepted
    );
    return this.getGroupSettings(deviceId, conversationId);
  }

  /**
   * Takes one person out of a group. Only the administrator may.
   *
   * The administrator cannot remove themselves here. Leaving is a separate decision
   * with its own consequences, and asking to remove oneself is far more likely to be
   * a mistake in the interface than a wish to lose the group.
   */
  async removeGroupMember(deviceId, conversationId, memberId) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    if (actor.conversation.type !== "GROUP") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "Only a group has a membership that can be changed"
      );
    }
    const target = this.requireIdentifier(memberId, {
      code: "INVALID_TARGET_MEMBER_ID",
      message: "Invalid target member id",
    });
    if (target === actor.memberId) {
      throw new ChatConversationError(
        400,
        "GROUP_ADMIN_CANNOT_REMOVE_SELF",
        "The administrator leaves a group instead of removing themselves"
      );
    }

    const settings = await this.getGroupSettings(deviceId, conversationId);
    if (!settings.members.some((member) => member.memberId === target)) {
      throw new ChatConversationError(
        404,
        "GROUP_MEMBER_NOT_FOUND",
        "This person is not in the group"
      );
    }

    await this.dbManager.removeGroupConversationMember(
      actor.conversation.id,
      target
    );
    await this.closeGroupIfTooSmall(actor.conversation.id);
    return this.getGroupSettings(deviceId, conversationId);
  }

  /**
   * Leaves a group.
   *
   * Any member may leave except the administrator. The administrator either hands
   * the group to somebody else or closes it, because a group whose administrator
   * walked away would have a name nobody can change, a membership nobody can change,
   * and no way out of that state — the others cannot remove an absent administrator
   * either.
   */
  async leaveGroup(deviceId, conversationId) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    if (actor.conversation.type === "DIRECT") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "A direct conversation is not left, it is simply not written in"
      );
    }
    const adminMemberId = await this.resolveGroupAdmin(actor.conversation);
    if (adminMemberId && adminMemberId === actor.memberId) {
      throw new ChatConversationError(
        409,
        "GROUP_ADMIN_CANNOT_LEAVE",
        "The administrator hands the group over or closes it instead of leaving"
      );
    }
    await this.dbManager.removeGroupConversationMember(
      actor.conversation.id,
      actor.memberId
    );
    const remaining = await this.closeGroupIfTooSmall(actor.conversation.id);
    const nextAdminMemberId = await this.resolveGroupAdmin(actor.conversation);
    return {
      conversationId: actor.conversation.id,
      left: true,
      remainingMembers: remaining.length,
      adminMemberId: nextAdminMemberId,
    };
  }

  /**
   * Hands administration of the group to another member.
   *
   * This is the way out for an administrator who no longer wants the group: the
   * group keeps working with somebody who does. The new administrator must already
   * be in the group — handing a group to somebody outside it would add a person to
   * a conversation they were never part of.
   */
  async transferGroupAdmin(deviceId, conversationId, memberId) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    if (actor.conversation.type !== "GROUP") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "Only a group has an administrator to hand over"
      );
    }
    const target = this.requireIdentifier(memberId, {
      code: "INVALID_TARGET_MEMBER_ID",
      message: "Invalid target member id",
    });
    if (target === actor.memberId) {
      throw new ChatConversationError(
        400,
        "GROUP_ADMIN_ALREADY",
        "Administration is already with this member"
      );
    }
    const settings = await this.getGroupSettings(deviceId, conversationId);
    if (!settings.members.some((member) => member.memberId === target)) {
      throw new ChatConversationError(
        404,
        "GROUP_MEMBER_NOT_FOUND",
        "This person is not in the group"
      );
    }
    await this.dbManager.updateGroupConversationAdmin(
      actor.conversation.id,
      target
    );
    return this.getGroupSettings(deviceId, conversationId);
  }

  /**
   * Closes the group for everybody.
   *
   * Marked inactive rather than deleted: closing a conversation is not a reason to
   * destroy what people wrote in it, and an inactive conversation is no longer
   * listed for anyone.
   */
  async closeGroup(deviceId, conversationId) {
    const actor = await this.requireGroupAdmin(deviceId, conversationId);
    if (actor.conversation.type !== "GROUP") {
      throw new ChatConversationError(
        400,
        "NOT_A_GROUP_CONVERSATION",
        "Only a group is closed by its administrator"
      );
    }
    await this.dbManager.deactivateChatConversation(actor.conversation.id);
    return { conversationId: actor.conversation.id, closed: true };
  }

  /**
   * Ends a conversation that too few people are left in.
   *
   * A group with one member is not a conversation, and leaving it in the list would
   * be a permanent reminder of a group that no longer exists. The messages are kept:
   * the conversation is only marked inactive, so nothing anybody wrote is destroyed
   * by somebody else's decision to leave.
   */
  async closeGroupIfTooSmall(conversationId) {
    const remaining = await this.dbManager.getGroupConversationMemberIds(
      conversationId
    );
    if (remaining.length < 2) {
      await this.dbManager.deactivateChatConversation(conversationId);
    }
    return remaining;
  }

  validateMessageInput(payload) {
    const clientMessageId = this.requireIdentifier(payload?.clientMessageId, {
      code: "INVALID_CLIENT_MESSAGE_ID",
      message: "Invalid client message id",
    });
    if (clientMessageId.length > MAX_CLIENT_MESSAGE_ID_LENGTH) {
      throw new ChatConversationError(
        400,
        "INVALID_CLIENT_MESSAGE_ID",
        "Invalid client message id"
      );
    }

    const messageType = payload?.messageType || "TEXT";
    const attachmentIds = payload?.attachmentIds || [];
    if (!["TEXT", "IMAGE", "FILE", "GIF", "VOICE"].includes(messageType) || !Array.isArray(attachmentIds) ||
        (messageType === "TEXT" ? attachmentIds.length !== 0 : attachmentIds.length !== 1) ||
        attachmentIds.some(id => typeof id !== "string" || !/^[0-9a-f-]{36}$/.test(id)))
      throw new ChatConversationError(400,"INVALID_MESSAGE_ATTACHMENT","Choose one attachment of the message type");
    const text = payload?.text ?? (messageType === "TEXT" ? undefined : "");
    if (typeof text !== "string" || (messageType === "TEXT" && !text.trim())) {
      throw new ChatConversationError(
        400,
        "INVALID_MESSAGE_TEXT",
        "Message text must not be empty"
      );
    }
    if (Buffer.byteLength(text, "utf8") > MAX_CHAT_TEXT_BYTES) {
      throw new ChatConversationError(
        413,
        "MESSAGE_TEXT_TOO_LARGE",
        "Message text exceeds 16 KiB"
      );
    }

    let clientSentAt = null;
    if (payload?.clientSentAt !== undefined && payload.clientSentAt !== null) {
      if (
        !Number.isSafeInteger(payload.clientSentAt) ||
        payload.clientSentAt <= 0
      ) {
        throw new ChatConversationError(
          400,
          "INVALID_CLIENT_SENT_AT",
          "clientSentAt must be a positive integer timestamp"
        );
      }
      clientSentAt = payload.clientSentAt;
    }

    return { clientMessageId, text, clientSentAt, messageType, attachmentIds };
  }

  async withReceipts(message) {
    if (!message) return null;
    const receipts = await this.dbManager.getChatMessageReceipts(message.id);
    const media = await this.attachments.media(message);
    const mediaFallback = !message.deletedAt && media.attachments.length > 0 && !message.text.trim();
    const isRead =
      message.legacyRead === true ||
      receipts.some((receipt) => receipt.readAt);
    const isDelivered =
      message.legacyDelivered === true ||
      receipts.some((receipt) => receipt.deliveredAt);
    const deliveryState = isRead
      ? "READ"
      : isDelivered
        ? "DELIVERED"
        : "ACCEPTED";
    return {
      ...this.formatMessage(message),
      ...media,
      text: mediaFallback ? `[Вложение: ${media.attachments[0].filename}]` : this.formatMessage(message).text,
      mediaFallback,
      deliveryState,
      receipts: receipts.map((receipt) => ({
        recipientMemberId: receipt.recipientMemberId,
        deliveredAt: receipt.deliveredAt || null,
        readAt: receipt.readAt || null,
      })),
    };
  }

  async sendMessage(deviceId, conversationId, payload) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    const input = this.validateMessageInput(payload);
    await this.attachments.ensure();
    let result;
    try {
      result = await this.dbManager.insertChatMessageV2({
        conversationId: actor.conversation.id,
        senderMemberId: actor.memberId,
        senderDeviceId: actor.deviceId,
        senderRoleSnapshot: actor.memberRole,
        clientMessageId: input.clientMessageId,
        text: input.text,
        messageType: input.messageType,
        bindAttachments: input.messageType === "TEXT" ? null : (id) => this.attachments.bind(actor, id, input.messageType, input.attachmentIds),
        clientSentAt: input.clientSentAt,
      });
    } catch (error) {
      if (
        error?.message ===
        "Client message id is already used in another conversation"
      ) {
        throw new ChatConversationError(
          409,
          "CLIENT_MESSAGE_ID_CONFLICT",
          "clientMessageId is already used in another conversation"
        );
      }
      throw error;
    }

    const storedMessage = result?.message?.id
      ? await this.dbManager.getChatMessageV2ById(result.message.id)
      : null;
    if (!storedMessage || storedMessage.conversationId !== actor.conversation.id) {
      throw new ChatConversationError(
        409,
        "CLIENT_MESSAGE_ID_CONFLICT",
        "clientMessageId is already used in another conversation"
      );
    }

    return {
      created: result.created === true,
      deduplicated: result.deduplicated === true,
      message: await this.withReceipts(storedMessage),
    };
  }

  async getMessages(deviceId, conversationId, options = {}) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    const beforeSequence =
      options.beforeSequence === undefined || options.beforeSequence === null
        ? null
        : this.parsePositiveInteger(options.beforeSequence, {
            code: "INVALID_BEFORE_SEQUENCE",
            message: "beforeSequence must be a positive integer",
          });
    const limit =
      options.limit === undefined || options.limit === null
        ? DEFAULT_PAGE_LIMIT
        : this.parsePositiveInteger(options.limit, {
            code: "INVALID_PAGE_LIMIT",
            message: `limit must be an integer between 1 and ${MAX_PAGE_LIMIT}`,
            maximum: MAX_PAGE_LIMIT,
          });
    const page = await this.dbManager.getChatMessagesV2Page(
      actor.conversation.id,
      { beforeSequence, limit }
    );
    // Messages this device removed for itself are skipped entirely, so a page
    // stays consistent for that device.
    const hidden = new Set(
      await this.dbManager.getChatMessageDeletionsForDevice(
        actor.conversation.id,
        actor.deviceId
      )
    );
    const visible = (page.messages || []).filter(
      (message) => !hidden.has(message.id)
    );
    const messages = await Promise.all(
      visible.map((message) => this.withReceipts(message))
    );
    return {
      ...page,
      conversationId: actor.conversation.id,
      messages,
    };
  }

  /**
   * Changes the text of the caller's own message.
   *
   * Only the author may edit, and only within a short window: rewriting an old
   * message after the others have read it would silently change what they saw.
   */
  async editMessage(deviceId, conversationId, messageId, payload) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    const message = await this.requireOwnMessage(actor, messageId);
    if (message.deletedAt) {
      throw new ChatConversationError(
        409,
        "MESSAGE_DELETED",
        "A withdrawn message cannot be edited"
      );
    }
    const age = Date.now() - Number(message.serverCreatedAt || 0);
    if (age > EDIT_WINDOW_MS) {
      throw new ChatConversationError(
        409,
        "EDIT_WINDOW_EXPIRED",
        "The message is too old to edit"
      );
    }
    const text = this.validateMessageText(payload?.text);
    await this.dbManager.updateChatMessageV2Text(message.id, text);
    const updated = await this.dbManager.getChatMessageV2ById(message.id);
    return { message: await this.withReceipts(updated) };
  }

  /**
   * Withdraws a message either for everyone or only for this device.
   *
   * Withdrawing for everyone is limited to the author; removing it from one's own
   * list is always allowed, because it only affects that device.
   */
  async deleteMessage(deviceId, conversationId, messageId, options = {}) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    const message = await this.dbManager.getChatMessageV2ById(messageId);
    if (!message || message.conversationId !== actor.conversation.id) {
      throw new ChatConversationError(
        404,
        "MESSAGE_NOT_FOUND",
        "Message not found in this conversation"
      );
    }

    const forEveryone = options.forEveryone === true;
    if (!forEveryone) {
      await this.dbManager.hideChatMessageV2ForDevice(
        message.id,
        actor.deviceId
      );
      return { messageId: message.id, forEveryone: false };
    }

    if (message.senderMemberId && message.senderMemberId !== actor.memberId) {
      throw new ChatConversationError(
        403,
        "MESSAGE_NOT_OWNED",
        "Only the author can withdraw a message for everyone"
      );
    }
    await this.dbManager.markChatMessageV2Deleted(message.id);
    const updated = await this.dbManager.getChatMessageV2ById(message.id);
    const result = {
      messageId: message.id,
      forEveryone: true,
      message: await this.withReceipts(updated),
    };
    await this.attachments.gc();
    return result;
  }

  /** Loads a message and asserts that the caller wrote it. */
  async requireOwnMessage(actor, messageId) {
    const message = await this.dbManager.getChatMessageV2ById(messageId);
    if (!message || message.conversationId !== actor.conversation.id) {
      throw new ChatConversationError(
        404,
        "MESSAGE_NOT_FOUND",
        "Message not found in this conversation"
      );
    }
    if (!message.senderMemberId || message.senderMemberId !== actor.memberId) {
      throw new ChatConversationError(
        403,
        "MESSAGE_NOT_OWNED",
        "Only the author can change this message"
      );
    }
    return message;
  }

  /** Message text rules shared by sending and editing. */
  validateMessageText(rawText) {
    const text = typeof rawText === "string" ? rawText.trim() : "";
    if (!text) {
      throw new ChatConversationError(
        400,
        "INVALID_MESSAGE_TEXT",
        "Message text must not be empty"
      );
    }
    // The same byte limit as sending. The previous character check referenced a
    // constant that does not exist, which would have thrown on a long message.
    if (Buffer.byteLength(text, "utf8") > MAX_CHAT_TEXT_BYTES) {
      throw new ChatConversationError(
        413,
        "MESSAGE_TEXT_TOO_LARGE",
        "Message text exceeds 16 KiB"
      );
    }
    return text;
  }

  async advanceReceipt(deviceId, conversationId, payload) {
    const actor = await this.resolveConversationActor(deviceId, conversationId);
    const hasDelivered = payload?.deliveredThroughSequence !== undefined;
    const hasRead = payload?.readThroughSequence !== undefined;
    if (!hasDelivered && !hasRead) {
      throw new ChatConversationError(
        400,
        "RECEIPT_SEQUENCE_REQUIRED",
        "A delivered or read sequence is required"
      );
    }
    const deliveredThroughSequence = hasDelivered
      ? this.parseReceiptSequence(payload.deliveredThroughSequence)
      : null;
    const readThroughSequence = hasRead
      ? this.parseReceiptSequence(payload.readThroughSequence)
      : null;

    const updated = await this.dbManager.advanceChatMemberReceipt({
      conversationId: actor.conversation.id,
      memberId: actor.memberId,
      deliveredThroughSequence,
      readThroughSequence,
      deviceId: actor.deviceId,
    });
    return {
      receipt: {
        conversationId: actor.conversation.id,
        memberId: actor.memberId,
        deliveredThroughSequence: Number(updated.lastDeliveredSequence) || 0,
        readThroughSequence: Number(updated.lastReadSequence) || 0,
      },
    };
  }
}

ChatConversationService.Error = ChatConversationError;
ChatConversationService.MAX_CHAT_TEXT_BYTES = MAX_CHAT_TEXT_BYTES;
ChatConversationService.DEFAULT_PAGE_LIMIT = DEFAULT_PAGE_LIMIT;
ChatConversationService.MAX_PAGE_LIMIT = MAX_PAGE_LIMIT;

module.exports = ChatConversationService;
