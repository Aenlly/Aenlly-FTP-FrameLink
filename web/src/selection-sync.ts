import { ApiError, type Choice, type Project } from "./api";
export type SyncState = "saved" | "saving" | "offline" | "conflict";
/** Serializes optimistic saves and retains edits made during an in-flight request. */
export class SelectionSync {
  private dirty = new Map<string, Choice>();
  private running: Promise<boolean> | null = null;
  private paused = false;
  constructor(
    public version: number,
    private save: (version: number, items: Choice[]) => Promise<Project>,
    private onState: (state: SyncState) => void,
    private onAck: (project: Project) => void,
    private onPending: (choices: Choice[], version: number) => void,
  ) {}
  edit(choice: Choice) {
    this.dirty.set(choice.mediaId, choice);
    this.onPending(this.pending(), this.version);
    this.onState(this.paused ? "conflict" : "saving");
  }
  pending() {
    return [...this.dirty.values()];
  }
  suspendConflict(){this.paused=true;this.onState('conflict')}
  restore(version: number, choices: Choice[], serverVersion: number) {
    this.version=version;
    for (const choice of choices) this.dirty.set(choice.mediaId,choice);
    this.paused=version!==serverVersion;
    this.onPending(this.pending(),this.version);
    this.onState(this.paused?"conflict":this.dirty.size?"saving":"saved");
  }
  rebase(version: number, keep: boolean) {
    this.version = version;
    this.paused = false;
    if (!keep) this.dirty.clear();
    this.onPending(this.pending(), this.version);
    this.onState(this.dirty.size ? "offline" : "saved");
  }
  flush(): Promise<boolean> {
    if (this.running) return this.running;
    if (this.paused) return Promise.resolve(false);
    this.running = this.drain().finally(() => {
      this.running = null;
    });
    return this.running;
  }
  private async drain() {
    try {
      while (this.dirty.size) {
        this.onState("saving");
        const batch = this.pending();
        const project = await this.save(this.version, batch);
        this.version = project.selectionVersion;
        for (const choice of batch) {
          if (
            JSON.stringify(this.dirty.get(choice.mediaId)) ===
            JSON.stringify(choice)
          )
            this.dirty.delete(choice.mediaId);
        }
        this.onPending(this.pending(), this.version);
        this.onAck(project);
      }
      this.onState("saved");
      return true;
    } catch (error) {
      this.paused = error instanceof ApiError && error.status === 409;
      this.onState(this.paused ? "conflict" : "offline");
      this.onPending(this.pending(), this.version);
      return false;
    }
  }
}
