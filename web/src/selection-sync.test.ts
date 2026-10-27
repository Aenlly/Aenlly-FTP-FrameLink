import { describe, it, expect } from "vitest";
import { ApiError, type Project, type Choice } from "./api";
import { SelectionSync } from "./selection-sync";
const project = (version: number) =>
  ({ id: "project", selectionVersion: version }) as Project;
const choice = (selected: boolean) => ({
  mediaId: "photo",
  selected,
  comment: "",
});
describe("selection autosave", () => {
  it("preserves the stale draft base across reloads until explicitly reconciled",async()=>{
    let persisted=0,calls=0;
    const sync=new SelectionSync(8,async()=>{calls++;return project(9)},()=>{},()=>{},(_,version)=>{persisted=version});
    sync.restore(5,[choice(true)],8);
    expect(persisted).toBe(5);
    expect(await sync.flush()).toBe(false);
    expect(calls).toBe(0);
    sync.rebase(8,true);
    expect(await sync.flush()).toBe(true);
  });
  it("retains an edit made while the previous choice is being saved", async () => {
    let release: (p: Project) => void = () => {};
    const sent: { version: number; items: Choice[] }[] = [];
    const sync = new SelectionSync(
      2,
      async (version, items) => {
        sent.push({ version, items });
        return sent.length === 1
          ? new Promise<Project>((resolve) => {
              release = resolve;
            })
          : project(version + 1);
      },
      () => {},
      () => {},
      () => {},
    );
    sync.edit(choice(true));
    const done = sync.flush();
    sync.edit(choice(false));
    release(project(3));
    expect(await done).toBe(true);
    expect(sent.map((x) => x.version)).toEqual([2, 3]);
    expect(sent[1].items[0].selected).toBe(false);
    expect(sync.pending()).toEqual([]);
  });
  it("keeps offline edits for a later retry", async () => {
    let online = false;
    const sync = new SelectionSync(
      0,
      async () => {
        if (!online) throw new TypeError("offline");
        return project(1);
      },
      () => {},
      () => {},
      () => {},
    );
    sync.edit(choice(true));
    expect(await sync.flush()).toBe(false);
    expect(sync.pending()).toHaveLength(1);
    online = true;
    expect(await sync.flush()).toBe(true);
  });
  it("requires explicit rebase after a competing client changes the version", async () => {
    let calls = 0;
    const sync = new SelectionSync(
      3,
      async (version) => {
        calls++;
        if (calls === 1) throw new ApiError(409, "conflict");
        return project(version + 1);
      },
      () => {},
      () => {},
      () => {},
    );
    sync.edit(choice(true));
    expect(await sync.flush()).toBe(false);
    expect(await sync.flush()).toBe(false);
    expect(calls).toBe(1);
    sync.rebase(8, true);
    expect(await sync.flush()).toBe(true);
    expect(sync.version).toBe(9);
  });
  it("discards only this device draft when choosing server results", async () => {
    const sync = new SelectionSync(
      0,
      async () => project(1),
      () => {},
      () => {},
      () => {},
    );
    sync.edit(choice(true));
    sync.rebase(4, false);
    expect(sync.pending()).toEqual([]);
    expect(sync.version).toBe(4);
  });
});
