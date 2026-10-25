# NoticeMate AI — Security Specification (Phase 0 TDD)

## 1. Data Invariants
1. **Strict Multi-Tenant Isolation**: Every document in `/users/{userId}/notices/{noticeId}` belongs exclusively to `userId`. Both the path parameter `{userId}` and the document field `userId` must equal `request.auth.uid`.
2. **Schema & Bounds Integrity**: All 27 required fields of `NoticeDocument` must be present, properly typed, and bounded in length/size (`groundingScore` in `0..100`, lists `<= 25` or `<= 50`, strings bounded).
3. **Immutable Metadata**: `userId` and `createdAt` cannot be modified on update.
4. **Temporal Integrity**: `createdAt` and `updatedAt` must be server timestamps (`is timestamp && <= request.time`).

## 2. The "Dirty Dozen" Adversarial Payloads
1. Unauthenticated read/list on `/users/alice_123/notices`.
2. Cross-user read (`bob_456` reading `/users/alice_123/notices/n1`).
3. Cross-user list query without matching `userId` filter.
4. Identity spoofing on create (`alice_123` writing `userId: "bob_456"`).
5. Shadow update injecting an undeclared ghost field (`isVerifiedAdmin: true`).
6. Immutable field tampering on update (mutating `createdAt` or `userId`).
7. Out-of-bounds integer (`groundingScore: 150` or `-5`).
8. Oversized string payload (`title` > 300 chars).
9. Oversized list payload (`checklistEn` > 25 items).
10. Invalid list element type (`completedIndices: ["not_an_int"]`).
11. Future timestamp spoofing (`createdAt` > `request.time`).
12. Path ID poisoning (`noticeId` containing invalid characters or > 128 chars).
