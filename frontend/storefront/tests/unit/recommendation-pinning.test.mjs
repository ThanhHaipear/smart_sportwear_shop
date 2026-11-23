// Review R2 (PR #1): a recommendation list may only be shown and tracked for the sources it was requested for.
// Run: npm run test:unit  (node --test; Node >= 22.18 strips the TypeScript types of pinning.ts itself).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { emptyList, nextPinned, sourceKeyOf } from "../../src/modules/recommendation/pinning.ts";

const A = sourceKeyOf(["shoe", "sock"]);
const B = sourceKeyOf(["jersey", "shorts"]);

const served = (sourceKey, requestId) => ({ sourceKey, requestId, modelVersion: "m1", items: [`${requestId}-item`] });

// Mirrors CartRecommendations + RecommendationSection: `latest` is the last response the cart component
// stored; `render` is what the section derives on each render (the derived-state loop settles in one step).
class CartBlock {
  constructor(productIds) {
    this.key = sourceKeyOf(productIds);
    this.latest = emptyList("");
    this.pinned = nextPinned(emptyList("\u0000"), this.key, this.latest);
  }
  setCart(productIds) { this.key = sourceKeyOf(productIds); return this.render(); }
  receive(response) { this.latest = response; return this.render(); }
  render() {
    const next = nextPinned(this.pinned, this.key, this.latest);
    if (next !== this.pinned) this.pinned = next;
    return this.pinned;
  }
  // What the user sees and what impression/click events would carry.
  get shownItems() { return this.pinned.items; }
  get trackedRequestId() { return this.pinned.items.length > 0 ? this.pinned.requestId : null; }
}

describe("sourceKeyOf", () => {
  test("ignores order and duplicates (quantity changes keep the key)", () => {
    assert.equal(sourceKeyOf(["b", "a", "a"]), sourceKeyOf(["a", "b"]));
  });
  test("changes when a product is added or removed", () => {
    assert.notEqual(sourceKeyOf(["a", "b"]), sourceKeyOf(["a"]));
    assert.equal(sourceKeyOf([]), "");
  });
});

describe("cart A -> cart B with a slow response for B", () => {
  test("while B loads, A's list is not shown or tracked under B; then B is shown with B's requestId", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.receive(served(A, "req-A"));
    assert.deepEqual(block.shownItems, ["req-A-item"]);
    assert.equal(block.trackedRequestId, "req-A");

    block.setCart(["jersey", "shorts"]); // B requested, response not back yet: latest is still A's
    assert.deepEqual(block.shownItems, []);
    assert.equal(block.trackedRequestId, null);
    block.render(); // a re-render while waiting changes nothing
    assert.deepEqual(block.shownItems, []);

    block.receive(served(B, "req-B"));
    assert.deepEqual(block.shownItems, ["req-B-item"]);
    assert.equal(block.trackedRequestId, "req-B");
  });

  test("a late response for A does not overwrite B", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.setCart(["jersey", "shorts"]);
    block.receive(served(B, "req-B"));
    block.receive(served(A, "req-A")); // A's request finished last
    assert.deepEqual(block.shownItems, ["req-B-item"]);
    assert.equal(block.trackedRequestId, "req-B");
  });

  test("an error response for B (empty list) shows nothing instead of A", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.receive(served(A, "req-A"));
    block.setCart(["jersey", "shorts"]);
    block.receive(emptyList(B));
    assert.deepEqual(block.shownItems, []);
    assert.equal(block.trackedRequestId, null);
  });
});

describe("other cart changes", () => {
  test("changing a quantity keeps the list and its requestId", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.receive(served(A, "req-A"));
    block.setCart(["sock", "shoe", "shoe"]); // same products, one more shoe
    assert.deepEqual(block.shownItems, ["req-A-item"]);
    assert.equal(block.trackedRequestId, "req-A");
  });

  test("removing every product hides the block", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.receive(served(A, "req-A"));
    block.setCart([]);
    assert.deepEqual(block.shownItems, []);
    block.receive(emptyList(""));
    assert.deepEqual(block.shownItems, []);
    assert.equal(block.trackedRequestId, null);
  });

  test("removing one product waits for the new list", () => {
    const block = new CartBlock(["shoe", "sock"]);
    block.receive(served(A, "req-A"));
    block.setCart(["shoe"]);
    assert.deepEqual(block.shownItems, []);
    block.receive(served(sourceKeyOf(["shoe"]), "req-shoe"));
    assert.equal(block.trackedRequestId, "req-shoe");
  });
});

describe("product detail page (server-rendered list)", () => {
  const P = sourceKeyOf(["shoe"]);

  test("router.refresh() serving a new requestId keeps the first list", () => {
    const first = served(P, "req-1");
    const pinned = nextPinned(emptyList("\u0000"), P, first);
    assert.equal(nextPinned(pinned, P, served(P, "req-2")), pinned);
  });

  test("a first render without a list is filled when the list arrives", () => {
    const pinned = nextPinned(emptyList("\u0000"), P, emptyList(P));
    assert.equal(nextPinned(pinned, P, served(P, "req-1")).requestId, "req-1");
  });

  test("navigating to another product takes that product's list", () => {
    const pinned = nextPinned(emptyList("\u0000"), P, served(P, "req-1"));
    const Q = sourceKeyOf(["sock"]);
    assert.equal(nextPinned(pinned, Q, served(Q, "req-q")).requestId, "req-q");
  });
});
