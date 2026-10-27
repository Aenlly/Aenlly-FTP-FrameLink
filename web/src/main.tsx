import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createRoot } from "react-dom/client";
import {
  Aperture,
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  Check,
  CheckCircle2,
  ChevronRight,
  Cloud,
  Copy,
  FolderOpen,
  Heart,
  ImagePlus,
  Link2,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  MessageCircle,
  Plus,
  RefreshCw,
  Share2,
  ShieldCheck,
  Upload,
  X,
} from "lucide-react";
import {
  ApiError,
  bytes,
  download,
  request,
  stateName,
  type Account,
  type Choice,
  type Media,
  type Project,
  type Share,
} from "./api";
import { SelectionSync, type SyncState } from "./selection-sync";
import "./styles.css";

const ownerKey = "framelink.owner";
const errorText = (error: unknown) =>
  error instanceof Error ? error.message : "操作未完成，请重试";
const date = (n: number) =>
  new Date(n).toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
function Brand() {
  return (
    <a className="brand" href="/">
      <span className="brand-icon">
        <Aperture size={23} />
      </span>
      <span>
        光屿<span className="brand-sub">FRAMELINK</span>
      </span>
    </a>
  );
}
function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    ref.current?.showModal();
    return () => ref.current?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? "dialog wide" : "dialog"}
      onCancel={onClose}
    >
      <header>
        <h2>{title}</h2>
        <button className="icon-button" onClick={onClose} aria-label="关闭">
          <X size={21} />
        </button>
      </header>
      {children}
    </dialog>
  );
}
function ErrorBanner({ message }: { message: string }) {
  return message ? (
    <div className="error" role="alert">
      {message}
    </div>
  ) : null;
}
function SecureImage({
  path,
  token,
  alt,
  className = "",
}: {
  path: string;
  token: string;
  alt: string;
  className?: string;
}) {
  const [url, setUrl] = useState(""),
    [failed, setFailed] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let objectUrl = "",
      active = true;
    const controller = new AbortController();
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting)) return;
        observer.disconnect();
        fetch("/api" + path, {
          headers: { Authorization: "Bearer " + token },
          signal: controller.signal,
        })
          .then(async (response) => {
            if (!response.ok) throw new Error("预览加载失败");
            return response.blob();
          })
          .then((blob) => {
            if (active) {
              objectUrl = URL.createObjectURL(blob);
              setUrl(objectUrl);
            }
          })
          .catch((error) => {
            if (active && error.name !== "AbortError") setFailed(true);
          });
      },
      { rootMargin: "180px" },
    );
    if (ref.current) observer.observe(ref.current);
    return () => {
      active = false;
      observer.disconnect();
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      setUrl("");
    };
  }, [path, token]);
  return (
    <div ref={ref} className={"secure-image " + className}>
      {url ? (
        <img src={url} alt={alt} decoding="async" />
      ) : (
        <div className="image-placeholder">
          {failed ? <span>预览暂不可用</span> : <Aperture size={28} />}
        </div>
      )}
    </div>
  );
}
function Empty({
  title,
  detail,
  children,
}: {
  title: string;
  detail: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-mark">
        <FolderOpen size={30} />
      </div>
      <h2>{title}</h2>
      <p>{detail}</p>
      {children}
    </div>
  );
}
function App() {
  const share = location.pathname.match(/^\/s\/([A-Za-z0-9_-]+)\/?$/)?.[1];
  const [token, setToken] = useState(localStorage.getItem(ownerKey) || "");
  if (share) return <Client share={share} />;
  return token ? (
    <Workspace
      token={token}
      onLogout={() => {
        localStorage.removeItem(ownerKey);
        setToken("");
      }}
    />
  ) : (
    <Login
      onLogin={(value) => {
        localStorage.setItem(ownerKey, value);
        setToken(value);
      }}
    />
  );
}
function Login({ onLogin }: { onLogin: (token: string) => void }) {
  const [register, setRegister] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  return (
    <main className="login-page">
      <div className="login-story">
        <Brand />
        <div>
          <p className="eyebrow">FOR THE MOMENTS YOU KEEP</p>
          <h1>
            让好照片，
            <br />
            抵达喜欢它的人。
          </h1>
          <p className="story-copy">
            从一次快门，到最后一张成片。
            <br />
            把选择、沟通与交付，留在同一个地方。
          </p>
          <div className="story-pills">
            <span>
              <Check size={14} /> 原片留在本地
            </span>
            <span>
              <Check size={14} /> 客户免安装选片
            </span>
          </div>
        </div>
        <p className="quiet">光屿 · 摄影师与照片之间，更轻的连接</p>
      </div>
      <section className="login-panel">
        <div className="login-card">
          <span className="section-kicker">YOUR CREATIVE SPACE</span>
          <h2>{register ? "开启你的光屿" : "欢迎回来"}</h2>
          <p className="muted">
            {register
              ? "使用邀请注册，建立你的第一个拍摄项目。"
              : "登录云端工作台，继续你的拍摄项目。"}
          </p>
          <ErrorBanner message={error} />
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              setBusy(true);
              setError("");
              try {
                const session = await request<{ token: string }>(
                  "/auth/" + (register ? "register" : "login"),
                  "",
                  { method: "POST", body: Object.fromEntries(data) },
                );
                onLogin(session.token);
              } catch (error) {
                setError(errorText(error));
              } finally {
                setBusy(false);
              }
            }}
          >
            <label>
              邮箱
              <input
                required
                type="email"
                name="email"
                autoComplete="email"
                placeholder="you@example.com"
              />
            </label>
            <label>
              密码
              <input
                required
                minLength={register ? 10 : 1}
                maxLength={72}
                type="password"
                name="password"
                autoComplete={register ? "new-password" : "current-password"}
                placeholder={register ? "至少10个字符" : "输入你的密码"}
              />
            </label>
            {register && (
              <label>
                邀请码
                <input
                  required
                  name="inviteCode"
                  placeholder="摄影师邀请测试邀请码"
                />
              </label>
            )}
            <button className="primary full" disabled={busy}>
              {busy ? <LoaderCircle className="spin" size={18} /> : null}
              {register ? "创建账号" : "登录工作台"}
              <ArrowRight size={18} />
            </button>
          </form>
          <button
            className="text-button"
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register ? "已有账号？登录" : "使用邀请码注册"}
          </button>
          <div className="privacy-note">
            <ShieldCheck size={17} />
            <span>
              本地相机功能不要求登录。这里是可选的云端选片与交付空间。
            </span>
          </div>
        </div>
      </section>
    </main>
  );
}
function Workspace({
  token,
  onLogout,
}: {
  token: string;
  onLogout: () => void;
}) {
  const [account, setAccount] = useState<Account | null>(null),
    [projects, setProjects] = useState<Project[]>([]),
    [current, setCurrent] = useState<string | null>(null),
    [creating, setCreating] = useState(false),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  const reload = useCallback(async () => {
    try {
      const [me, list] = await Promise.all([
        request<Account>("/auth/me", token),
        request<Project[]>("/projects", token),
      ]);
      setAccount(me);
      setProjects(list);
      setError("");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onLogout();
      else setError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, [token]);
  useEffect(() => {
    void reload();
  }, [reload]);
  return (
    <>
      <header className="site-header">
        <Brand />
        <nav>
          <button className="nav-active" onClick={() => setCurrent(null)}>
            拍摄项目
          </button>
          <a
            href="https://github.com/Aenlly/Aenlly-FTP"
            target="_blank"
            rel="noreferrer"
          >
            开源起点
          </a>
        </nav>
        <div className="account">
          <span className="account-avatar">
            {account?.email[0]?.toUpperCase() || "F"}
          </span>
          <span>{account?.email}</span>
          <button
            aria-label="退出登录"
            className="icon-button"
            onClick={async () => {
              await request("/auth/logout", token, { method: "POST" }).catch(
                () => {},
              );
              onLogout();
            }}
          >
            <LogOut size={18} />
          </button>
        </div>
      </header>
      <main className="workspace">
        <ErrorBanner message={error} />
        {current ? (
          <OwnerProject
            id={current}
            token={token}
            onBack={() => {
              setCurrent(null);
              void reload();
            }}
            onChange={reload}
          />
        ) : (
          <>
            <section className="workspace-hero">
              <div>
                <p className="eyebrow">
                  YOUR PHOTOGRAPHY, BEAUTIFULLY CONNECTED
                </p>
                <h1>
                  每一次拍摄，
                  <br className="mobile-only" />
                  都有好结果。
                </h1>
                <p className="muted">
                  整理作品，邀请客户挑选，把最后的成片安心交付。
                </p>
              </div>
              <button className="primary" onClick={() => setCreating(true)}>
                <Plus size={19} />
                新建拍摄项目
              </button>
            </section>
            <section className="summary-strip">
              <div>
                <FolderOpen size={19} />
                <span>拍摄项目</span>
                <strong>{projects.length.toString().padStart(2, "0")}</strong>
              </div>
              <div>
                <CheckCircle2 size={19} />
                <span>等待确认</span>
                <strong>
                  {projects
                    .filter((p) => p.state === "SUBMITTED")
                    .length.toString()
                    .padStart(2, "0")}
                </strong>
              </div>
              <div className="storage-summary">
                <Cloud size={19} />
                <span>云端空间</span>
                <strong>
                  {bytes(account?.usedBytes || 0)}
                  <small> / {bytes(account?.quotaBytes || 0)}</small>
                </strong>
              </div>
            </section>
            <div className="section-heading">
              <h2>
                我的项目<span>{projects.length}</span>
              </h2>
              <button className="text-button" onClick={() => void reload()}>
                <RefreshCw size={15} />
                刷新
              </button>
            </div>
            {loading ? (
              <div className="loading">
                <LoaderCircle className="spin" />
                正在打开你的工作台
              </div>
            ) : projects.length ? (
              <div className="project-grid">
                {projects.map((project, index) => (
                  <button
                    className="project-card"
                    key={project.id}
                    onClick={() => setCurrent(project.id)}
                  >
                    <div className={"project-cover cover-" + (index % 4)}>
                      <span className="project-initial">
                        {project.name.slice(0, 1)}
                      </span>
                      <span className="cover-grain" />
                      <span className="project-state">
                        {stateName[project.state]}
                      </span>
                      <ArrowRight className="cover-arrow" size={22} />
                    </div>
                    <div className="project-card-copy">
                      <h3>{project.name}</h3>
                      <p>
                        <span>
                          {project.mediaCount || 0} 张预览 · 第{" "}
                          {project.roundNo} 轮
                        </span>
                        <span>{date(project.updatedAt)}</span>
                      </p>
                    </div>
                  </button>
                ))}
                <button
                  className="new-project-card"
                  onClick={() => setCreating(true)}
                >
                  <span>
                    <Plus size={26} />
                  </span>
                  <strong>开始下一次拍摄</strong>
                  <small>让照片有序，也让交付从容</small>
                </button>
              </div>
            ) : (
              <Empty
                title="从一个拍摄项目开始"
                detail="加入预览照片，分享给客户。选择与留言，会自动回到这里。"
              >
                <button className="primary" onClick={() => setCreating(true)}>
                  <Plus size={18} />
                  新建项目
                </button>
              </Empty>
            )}
            <footer className="workspace-footer">
              <span>为创作留白，为交付收尾。</span>
              <span>光屿 FRAMELINK</span>
            </footer>
          </>
        )}
      </main>
      {creating && (
        <Modal title="新的拍摄项目" onClose={() => setCreating(false)}>
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const name = new FormData(event.currentTarget).get(
                "name",
              ) as string;
              try {
                const project = await request<Project>("/projects", token, {
                  method: "POST",
                  body: { name },
                });
                setCreating(false);
                setCurrent(project.id);
                void reload();
              } catch (error) {
                setError(errorText(error));
              }
            }}
          >
            <p className="muted">
              用客户、主题或拍摄日期命名，之后更容易找到。
            </p>
            <label>
              项目名称
              <input
                autoFocus
                required
                maxLength={160}
                name="name"
                placeholder="例如：秋日下午 · 人像拍摄"
              />
            </label>
            <button className="primary full">
              创建项目
              <ArrowRight size={18} />
            </button>
          </form>
        </Modal>
      )}
    </>
  );
}
function OwnerProject({
  id,
  token,
  onBack,
  onChange,
}: {
  id: string;
  token: string;
  onBack: () => void;
  onChange: () => void;
}) {
  const [project, setProject] = useState<Project | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(""),
    [sharing, setSharing] = useState(false),
    [createdShare, setCreatedShare] = useState<Share | null>(null),
    [viewer, setViewer] = useState<Media | null>(null),
    [filter, setFilter] = useState("all");
  const input = useRef<HTMLInputElement>(null),
    delivery = useRef<HTMLInputElement>(null),
    parent = useRef("");
  const latestVersion = useRef("");
  const versionKey = (project: Pick<Project,"state"|"roundNo"|"selectionVersion"|"updatedAt">) => JSON.stringify([project.state,project.roundNo,project.selectionVersion,project.updatedAt]);
  const load = useCallback(async () => {
    const result=await request<Project>("/projects/" + id, token);
    latestVersion.current=versionKey(result);
    setProject(result);
  }, [id, token]);
  useEffect(() => {
    void load().catch((e) => setError(errorText(e)));
    let active=true;
    const refresh=async()=>{if(document.hidden||!active)return;try{const status=await request<Project>("/projects/"+id+"/status",token);if(active&&versionKey(status)!==latestVersion.current)await load()}catch(error){if(active)setError(errorText(error))}};
    const timer=setInterval(()=>void refresh(),10000);
    window.addEventListener("focus",refresh);window.addEventListener("online",refresh);
    return()=>{active=false;clearInterval(timer);window.removeEventListener("focus",refresh);window.removeEventListener("online",refresh)};
  }, [load]);
  const run = async (action: () => Promise<unknown>, label = "处理中") => {
    setBusy(label);
    setError("");
    try {
      await action();
      await load();
      onChange();
    } catch (error) {
      setError(errorText(error));
    } finally {
      setBusy("");
    }
  };
  const media = project?.media?.filter((m) => m.kind === "PREVIEW") || [],
    shown = media.filter((m) => filter !== "selected" || m.selected),
    latest = project?.revisions?.[0];
  const upload = async (files: FileList | null, parentId?: string) => {
    if (!files) return;
    await run(async () => {
      let index = 0;
      for (const file of Array.from(files)) {
        setBusy(`正在上传 ${++index} / ${files.length}`);
        const form = new FormData();
        form.set("file", file);
        form.set("kind", parentId ? "DELIVERY" : "PREVIEW");
        if (parentId) form.set("parentId", parentId);
        await request("/projects/" + id + "/media", token, {
          method: "POST",
          body: form,
        });
      }
    }, "正在上传");
  };
  if (!project)
    return (
      <>
        <button className="back-button" onClick={onBack}>
          <ArrowLeft size={17} />
          返回项目
        </button>
        <ErrorBanner message={error} />
        <div className="loading">
          <LoaderCircle className="spin" />
          正在打开项目
        </div>
      </>
    );
  return (
    <>
      <button className="back-button" onClick={onBack}>
        <ArrowLeft size={17} />
        所有拍摄项目
      </button>
      <section className="project-heading">
        <div>
          <p className="eyebrow">
            PHOTOGRAPHY PROJECT · ROUND{" "}
            {project.roundNo.toString().padStart(2, "0")}
          </p>
          <h1>{project.name}</h1>
          <p className="muted">
            <span className={"status-dot " + project.state.toLowerCase()} />
            {stateName[project.state]}
            <span className="divider">/</span>
            {media.length} 张预览<span className="divider">/</span>
            {media.filter((m) => m.selected).length} 张已选
          </p>
        </div>
        <div className="action-row">
          {["DRAFT", "SHARED"].includes(project.state) && (
            <button
              className="secondary"
              disabled={!!busy}
              onClick={() => input.current?.click()}
            >
              <ImagePlus size={18} />
              加入预览
            </button>
          )}
          <button
            className="primary"
            disabled={!media.length || !!busy}
            onClick={() => setSharing(true)}
          >
            <Share2 size={18} />
            客户分享
          </button>
        </div>
      </section>
      <input
        hidden
        multiple
        type="file"
        accept="image/jpeg,image/png"
        ref={input}
        onChange={(e) => {
          void upload(e.target.files);
          e.target.value = "";
        }}
      />
      <input
        hidden
        type="file"
        ref={delivery}
        onChange={(e) => {
          void upload(e.target.files, parent.current);
          e.target.value = "";
        }}
      />
      <ErrorBanner message={error} />
      {busy && (
        <div className="activity-bar" role="status">
          <LoaderCircle className="spin" size={16} />
          {busy}，请保留当前页面
        </div>
      )}
      {project.state === "SUBMITTED" && latest && (
        <section className="review-banner">
          <div className="review-icon">
            <CheckCircle2 size={24} />
          </div>
          <div>
            <h3>客户已提交第 {project.roundNo} 轮选片</h3>
            <p>
              共选 {latest.items.filter((i) => i.selected).length} 张 ·{" "}
              {new Date(latest.submittedAt).toLocaleString("zh-CN")}
            </p>
          </div>
          <button
            className="primary"
            disabled={!!busy}
            onClick={() =>
              void run(() =>
                request("/projects/" + id + "/confirm", token, {
                  method: "POST",
                  body: { revisionId: latest.id },
                }),
              )
            }
          >
            确认本轮选片
            <Check size={17} />
          </button>
        </section>
      )}
      {["CONFIRMED", "DELIVERED"].includes(project.state) && (
        <section className="review-banner confirmed">
          <ShieldCheck size={25} />
          <div>
            <h3>本轮选择已确认，可以交付成片</h3>
            <p>在选中照片下上传对应成片，再为客户开放下载。</p>
          </div>
          <button
            className="secondary"
            disabled={!!busy}
            onClick={() => {
              if (
                confirm(
                  "开放新一轮后，客户可继续改选，当前交付会暂时隐藏。已有提交历史会保留。",
                )
              )
                void run(() =>
                  request("/projects/" + id + "/reopen", token, {
                    method: "POST",
                  }),
                );
            }}
          >
            开放新一轮
          </button>
        </section>
      )}
      <div className="gallery-toolbar">
        <div className="segmented">
          <button
            className={filter === "all" ? "active" : ""}
            onClick={() => setFilter("all")}
          >
            全部照片<span>{media.length}</span>
          </button>
          <button
            className={filter === "selected" ? "active" : ""}
            onClick={() => setFilter("selected")}
          >
            客户已选<span>{media.filter((m) => m.selected).length}</span>
          </button>
        </div>
        <button
          className="text-button"
          onClick={() =>
            void download(
              "/projects/" + id + "/selection.csv",
              token,
              "选片清单.csv",
            ).catch((e) => setError(errorText(e)))
          }
        >
          <ArrowDownToLine size={16} />
          导出选片清单
        </button>
      </div>
      {shown.length ? (
        <div className="photo-grid">
          {shown.map((item) => (
            <article
              className={"photo-card " + (item.selected ? "selected" : "")}
              key={item.id}
            >
              <button
                className="photo-open"
                onClick={() => setViewer(item)}
                aria-label={"查看 " + item.name}
              >
                <SecureImage
                  path={"/projects/" + id + "/media/" + item.id + "/content"}
                  token={token}
                  alt={item.name}
                />
                {item.selected && (
                  <span className="selected-badge">
                    <Check size={14} />
                    客户已选
                  </span>
                )}
              </button>
              <div className="photo-copy">
                <div>
                  <span title={item.name}>{item.name}</span>
                  <small>{bytes(item.bytes)} · 选片预览</small>
                </div>
                {item.commentText && <MessageCircle size={16} />}
              </div>
              {item.commentText && (
                <p className="photo-comment">{item.commentText}</p>
              )}
              {item.selected &&
                ["CONFIRMED", "DELIVERED"].includes(project.state) && (
                  <button
                    className="delivery-button"
                    disabled={!!busy}
                    onClick={() => {
                      parent.current = item.id;
                      delivery.current?.click();
                    }}
                  >
                    <Upload size={15} />
                    上传对应成片
                    <span>
                      {project.media?.filter((m) => m.parentId === item.id)
                        .length || 0}{" "}
                      份
                    </span>
                  </button>
                )}
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title={
            filter === "selected" ? "等待客户的选择" : "把照片加入这个项目"
          }
          detail={
            filter === "selected"
              ? "分享链接后，客户的选择和留言会同步显示。"
              : "上传JPEG或PNG，系统会生成去除定位信息的独立预览。原片由你保管。"
          }
        >
          {filter !== "selected" && (
            <button className="primary" onClick={() => input.current?.click()}>
              <ImagePlus size={18} />
              加入预览照片
            </button>
          )}
        </Empty>
      )}
      {project.revisions && project.revisions.length > 0 && (
        <section className="history">
          <h2>选片记录</h2>
          {project.revisions.map((revision) => (
            <div key={revision.id}>
              <span className="round-number">
                {revision.roundNo.toString().padStart(2, "0")}
              </span>
              <span>
                第 {revision.roundNo} 轮提交
                <small>
                  {new Date(revision.submittedAt).toLocaleString("zh-CN")}
                </small>
              </span>
              <strong>
                {revision.items.filter((i) => i.selected).length} 张已选
              </strong>
            </div>
          ))}
        </section>
      )}
      {sharing && (
        <Modal title="分享给客户" onClose={() => setSharing(false)}>
          <p className="muted">
            客户通过链接与访问码选片，无需安装App。新链接会让旧链接失效。
          </p>
          <ErrorBanner message={error} />
          {createdShare && (
            <div className="share-result">
              <label>
                客户链接
                <input readOnly value={createdShare.url} />
              </label>
              <div className="share-code">
                <span>访问码</span>
                <strong>{createdShare.accessCode}</strong>
              </div>
              <button
                className="primary full"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(
                      `请查看「${project.name}」并选择喜欢的照片：\n${createdShare.url}\n访问码：${createdShare.accessCode}`,
                    );
                    setBusy("分享内容已复制");
                    setTimeout(() => setBusy(""), 1500);
                  } catch {
                    setError("请手动复制上方链接与访问码");
                  }
                }}
              >
                <Copy size={17} />
                复制分享内容
              </button>
              <a
                className="text-button"
                href={createdShare.url}
                target="_blank"
                rel="noreferrer"
              >
                预览客户页面
                <ChevronRight size={15} />
              </a>
            </div>
          )}
          <button
            className="secondary full"
            disabled={!!busy}
            onClick={() =>
              void run(async () => {
                setCreatedShare(
                  await request<Share>("/projects/" + id + "/shares", token, {
                    method: "POST",
                    body: { days: 7, allowDownload: false },
                  }),
                );
              })
            }
          >
            <Link2 size={17} />
            {createdShare ? "重新生成链接" : "生成7天有效链接"}
          </button>
          {project.shares
            ?.filter((s) => !s.revoked)
            .map((share) => (
              <div className="share-settings" key={share.id}>
                <p>
                  有效期至 {new Date(share.expiresAt).toLocaleString("zh-CN")}
                </p>
                <label className="check-label">
                  <input
                    type="checkbox"
                    disabled={!!busy}
                    checked={share.allowDownload}
                    onChange={(e) =>
                      void run(() =>
                        request(
                          "/projects/" + id + "/shares/" + share.id,
                          token,
                          {
                            method: "PATCH",
                            body: { allowDownload: e.target.checked },
                          },
                        ),
                      )
                    }
                  />
                  允许客户下载已交付成片
                </label>
                {busy && <p role="status">正在保存分享设置…</p>}
                <div className="action-row">
                  <button
                    className="text-button"
                    onClick={() =>
                      void run(() =>
                        request(
                          "/projects/" + id + "/shares/" + share.id,
                          token,
                          { method: "PATCH", body: { days: 7 } },
                        ),
                      )
                    }
                  >
                    延长至7天后
                  </button>
                  <button
                    className="text-button danger"
                    onClick={() => {
                      if (confirm("撤销后，客户将无法继续浏览或下载。"))
                        void run(() =>
                          request(
                            "/projects/" + id + "/shares/" + share.id,
                            token,
                            { method: "DELETE" },
                          ),
                        );
                    }}
                  >
                    撤销访问
                  </button>
                </div>
              </div>
            ))}
        </Modal>
      )}
      {viewer && (
        <Viewer
          items={shown}
          current={viewer}
          token={token}
          path={(m) => "/projects/" + id + "/media/" + m.id + "/content"}
          onClose={() => setViewer(null)}
          onMove={setViewer}
        />
      )}
    </>
  );
}
function Client({ share }: { share: string }) {
  const key = "framelink.visitor." + share,
    draftKey = key + ".draft";
  const [token, setToken] = useState(localStorage.getItem(key) || ""),
    [project, setProject] = useState<Project | null>(null),
    [error, setError] = useState(""),
    [status, setStatus] = useState<SyncState>("saved"),
    [filter, setFilter] = useState("all"),
    [pending, setPending] = useState<Choice[]>([]),
    [viewer, setViewer] = useState<Media | null>(null),
    [submitting, setSubmitting] = useState(false);
  const sync = useRef<SelectionSync | null>(null),
    timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const load = useCallback(async () => {
    const result = await request<Project>(
      "/public/" + share + "/project",
      token,
    );
    setProject(result);
    return result;
  }, [share, token]);
  useEffect(() => {
    if (!token) return;
    let active = true;
    void load()
      .then((result) => {
        if (!active) return;
        const controller = new SelectionSync(
          result.selectionVersion,
          (version, items) =>
            request<Project>("/public/" + share + "/selection", token, {
              method: "PUT",
              body: { version, items },
            }),
          setStatus,
          setProject,
          (choices, version) => {
            setPending(choices);
            if (choices.length)
              localStorage.setItem(
                draftKey,
                JSON.stringify({ version, items: choices }),
              );
            else localStorage.removeItem(draftKey);
          },
        );
        sync.current = controller;
        try {
          const raw = localStorage.getItem(draftKey);
          if (raw) {
            const draft = JSON.parse(raw) as {
              version: number;
              items: Choice[];
            };
            controller.restore(draft.version,draft.items,result.selectionVersion);
            if (draft.version === result.selectionVersion)
              void controller.flush();
            else {
              controller.suspendConflict();
            }
          }
        } catch {
          setError("本机暂存选择无法恢复，请核对服务器结果。");
        }
      })
      .catch((error) => {
        if (error instanceof ApiError && error.status === 401) {
          localStorage.removeItem(key);
          setToken("");
        }
        setError(errorText(error));
      });
    return () => {
      active = false;
      if (timer.current) clearTimeout(timer.current);
      sync.current = null;
    };
  }, [token, load]);
  useEffect(() => {
    const online = () => {
      void sync.current?.flush();
    };
    window.addEventListener("online", online);
    return () => window.removeEventListener("online", online);
  }, []);
  const edit = (item: Media, patch: Partial<Choice>) => {
    if (project?.state !== "SHARED") return;
    const current = pending.find((c) => c.mediaId === item.id) || {
      mediaId: item.id,
      selected: item.selected,
      comment: item.commentText || "",
    };
    sync.current?.edit({ ...current, ...patch });
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void sync.current?.flush(), 700);
  };
  const choices = (project?.media || []).map((item) => {
      const change = pending.find((c) => c.mediaId === item.id);
      return change
        ? { ...item, selected: change.selected, commentText: change.comment }
        : item;
    }),
    photos = choices.filter((m) => m.kind === "PREVIEW"),
    shown = photos.filter((m) => filter !== "selected" || m.selected),
    count = photos.filter((m) => m.selected).length;
  const changeVersion = async (keep: boolean) => {
    try {
      const latest = await load();
      sync.current?.rebase(latest.selectionVersion, keep);
      if (keep) await sync.current?.flush();
    } catch (error) {
      setError(errorText(error));
    }
  };
  if (!token)
    return (
      <div className="guest-entry">
        <Brand />
        <section className="guest-unlock">
          <div className="lock-mark">
            <LockKeyhole size={29} />
          </div>
          <p className="eyebrow">A PRIVATE GALLERY, JUST FOR YOU</p>
          <h1>
            你的照片，
            <br />
            等你来挑选。
          </h1>
          <p className="muted">输入摄影师提供的访问码，打开专属选片空间。</p>
          <ErrorBanner message={error} />
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              try {
                const accessCode = new FormData(event.currentTarget).get(
                  "accessCode",
                );
                const result = await request<{ token: string }>(
                  "/public/" + share + "/unlock",
                  "",
                  { method: "POST", body: { accessCode } },
                );
                localStorage.setItem(key, result.token);
                setError("");
                setToken(result.token);
              } catch (error) {
                setError(errorText(error));
              }
            }}
          >
            <input
              aria-label="访问码"
              name="accessCode"
              className="code-input"
              required
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              autoComplete="one-time-code"
              placeholder="6 位访问码"
            />
            <button className="primary full">
              打开我的照片
              <ArrowRight size={18} />
            </button>
          </form>
          <small>
            <ShieldCheck size={14} />
            仅获得授权的访客可以查看
          </small>
        </section>
      </div>
    );
  return (
    <>
      <header className="guest-header">
        <Brand />
        <span>
          <ShieldCheck size={16} />
          私人选片空间
        </span>
      </header>
      <main className="client-workspace">
        <ErrorBanner message={error} />
        {project ? (
          <>
            <section className="client-heading">
              <p className="eyebrow">
                YOUR MOMENTS · ROUND{" "}
                {project.roundNo.toString().padStart(2, "0")}
              </p>
              <h1>{project.name}</h1>
              <p className="muted">
                慢慢看，选出你喜欢的。也可以给每张照片留一句话。
              </p>
              <div className="client-meta">
                <span>{photos.length} 张照片</span>
                <span>第 {project.roundNo} 轮选片</span>
                <span>有效期至 {date(project.expiresAt || Date.now())}</span>
              </div>
            </section>
            {status === "conflict" && (
              <div className="conflict">
                <p>
                  选片已在其他页面更新。你的本机选择仍然保留，请核对后决定如何继续。
                </p>
                <button
                  className="secondary"
                  onClick={() => void changeVersion(true)}
                >
                  更新本机待保存项
                </button>
                <button
                  className="text-button"
                  onClick={() => void changeVersion(false)}
                >
                  使用服务器结果
                </button>
              </div>
            )}
            <div className="gallery-toolbar">
              <div className="segmented">
                <button
                  className={filter === "all" ? "active" : ""}
                  onClick={() => setFilter("all")}
                >
                  全部照片<span>{photos.length}</span>
                </button>
                <button
                  className={filter === "selected" ? "active" : ""}
                  onClick={() => setFilter("selected")}
                >
                  我的选择<span>{count}</span>
                </button>
              </div>
              <span className={"save-status " + status}>
                {status === "saving" ? (
                  <LoaderCircle size={14} className="spin" />
                ) : (
                  <Check size={14} />
                )}
                {
                  {
                    saved: "选择已保存",
                    saving: "正在保存",
                    offline: "已暂存此设备",
                    conflict: "需要核对更新",
                  }[status]
                }
                {status === "offline" && (
                  <button
                    className="text-button"
                    onClick={() => void sync.current?.flush()}
                  >
                    重试
                  </button>
                )}
              </span>
            </div>
            {project.state !== "SHARED" && (
              <div className="review-banner confirmed">
                <CheckCircle2 size={24} />
                <div>
                  <h3>
                    {project.state === "SUBMITTED"
                      ? "选择已提交给摄影师"
                      : "本轮选片已确认"}
                  </h3>
                  <p>需要调整时，请联系摄影师开放新一轮。</p>
                </div>
              </div>
            )}
            <div className="photo-grid client-grid">
              {shown.map((item) => (
                <article
                  key={item.id}
                  className={"photo-card " + (item.selected ? "selected" : "")}
                >
                  <button
                    className="photo-open"
                    aria-label={"查看 " + item.name}
                    onClick={() => setViewer(item)}
                  >
                    <SecureImage
                      path={"/public/" + share + "/media/" + item.id}
                      token={token}
                      alt={item.name}
                    />
                  </button>
                  <button
                    className={"pick-button " + (item.selected ? "picked" : "")}
                    aria-label={
                      (item.selected ? "取消选择 " : "选择 ") + item.name
                    }
                    disabled={project.state !== "SHARED"}
                    onClick={() => edit(item, { selected: !item.selected })}
                  >
                    <Heart
                      size={18}
                      fill={item.selected ? "currentColor" : "none"}
                    />
                  </button>
                  <div className="photo-copy">
                    <span>{item.name}</span>
                    <span className="preview-label">预览</span>
                  </div>
                  {item.commentText && (
                    <p className="photo-comment">{item.commentText}</p>
                  )}
                </article>
              ))}
            </div>
            {project.allowDownload &&
              ["CONFIRMED", "DELIVERED"].includes(project.state) &&
              choices.some((m) => m.kind === "DELIVERY") && (
                <section className="deliveries">
                  <p className="eyebrow">READY FOR YOU</p>
                  <h2>你的成片，已准备好。</h2>
                  <p className="muted">摄影师交付的文件会保留完整画质。</p>
                  {choices
                    .filter((m) => m.kind === "DELIVERY")
                    .map((file) => (
                      <button
                        className="delivery-item"
                        key={file.id}
                        onClick={() =>
                          void download(
                            "/public/" +
                              share +
                              "/media/" +
                              file.id +
                              "?download=true",
                            token,
                            file.name,
                          ).catch((e) => setError(errorText(e)))
                        }
                      >
                        <CheckCircle2 size={21} />
                        <span>
                          {file.name}
                          <small>{bytes(file.bytes)}</small>
                        </span>
                        <ArrowDownToLine size={19} />
                      </button>
                    ))}
                </section>
              )}
            <div className="client-dock">
              <div>
                <strong>
                  {count}
                  <small> 张已选</small>
                </strong>
                <span>
                  {project.state === "SHARED"
                    ? "选好后，提交给摄影师确认。"
                    : "选择与留言已经记录。"}
                </span>
              </div>
              {project.state === "SHARED" ? (
                <button
                  className="primary"
                  disabled={!count || submitting || status === "conflict"}
                  onClick={async () => {
                    if (
                      !confirm(
                        `确认提交这 ${count} 张照片？提交后由摄影师确认，需要改选可联系摄影师。`,
                      )
                    )
                      return;
                    setSubmitting(true);
                    try {
                      if (timer.current) clearTimeout(timer.current);
                      const controller = sync.current; if (!controller || !(await controller.flush()))
                        throw new Error("还有选择尚未保存，请稍后重试");
                      await request("/public/" + share + "/submit", token, {
                        method: "POST",
                        body: { version: controller.version },
                      });
                      await load();
                    } catch (error) {
                      setError(errorText(error));
                    } finally {
                      setSubmitting(false);
                    }
                  }}
                >
                  {submitting ? (
                    <LoaderCircle size={17} className="spin" />
                  ) : (
                    <Check size={18} />
                  )}
                  提交选择
                </button>
              ) : (
                <span className="submitted-pill">
                  <CheckCircle2 size={18} />
                  已提交
                </span>
              )}
            </div>
          </>
        ) : (
          <div className="loading">
            <LoaderCircle className="spin" />
            正在打开你的照片
          </div>
        )}
      </main>
      {viewer && (
        <Viewer
          items={shown}
          current={choices.find((m) => m.id === viewer.id) || viewer}
          token={token}
          path={(m) => "/public/" + share + "/media/" + m.id}
          onClose={() => setViewer(null)}
          onMove={setViewer}
          onPick={
            project?.state === "SHARED"
              ? (item) => edit(item, { selected: !item.selected })
              : undefined
          }
          onComment={
            project?.state === "SHARED"
              ? (item, comment) => edit(item, { comment })
              : undefined
          }
        />
      )}
    </>
  );
}
function Viewer({
  items,
  current,
  token,
  path,
  onClose,
  onMove,
  onPick,
  onComment,
}: {
  items: Media[];
  current: Media;
  token: string;
  path: (media: Media) => string;
  onClose: () => void;
  onMove: (media: Media) => void;
  onPick?: (media: Media) => void;
  onComment?: (media: Media, comment: string) => void;
}) {
  const index = items.findIndex((m) => m.id === current.id);
  const move = (delta: number) => {
    if (items[index + delta]) onMove(items[index + delta]);
  };
  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      if ((event.target as HTMLElement).tagName === "TEXTAREA") return;
      if (event.key === "Escape") onClose();
      if (event.key === "ArrowRight") move(1);
      if (event.key === "ArrowLeft") move(-1);
    };
    window.addEventListener("keydown", handle);
    const old = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", handle);
      document.body.style.overflow = old;
    };
  }, [index, items]);
  return (
    <div
      className="viewer"
      role="dialog"
      aria-modal="true"
      aria-label="照片预览"
    >
      <header>
        <div>
          <strong>{current.name}</strong>
          <span>
            选片预览 · {index + 1} / {items.length}
          </span>
        </div>
        <button
          className="icon-button"
          aria-label="关闭照片预览"
          onClick={onClose}
        >
          <X size={24} />
        </button>
      </header>
      <div className="viewer-stage">
        <button
          className="viewer-prev"
          disabled={index === 0}
          onClick={() => move(-1)}
          aria-label="上一张"
        >
          <ArrowLeft />
        </button>
        <SecureImage
          key={current.id}
          path={path(current)}
          token={token}
          alt={current.name}
        />
        <button
          className="viewer-next"
          disabled={index === items.length - 1}
          onClick={() => move(1)}
          aria-label="下一张"
        >
          <ArrowRight />
        </button>
      </div>
      <footer>
        <div>
          {onComment ? (
            <textarea
              aria-label="给摄影师的留言"
              maxLength={2000}
              placeholder="给这张照片留一句话，例如：喜欢这个表情，希望稍微提亮。"
              value={current.commentText || ""}
              onChange={(e) => onComment(current, e.target.value)}
            />
          ) : (
            <p>
              {current.commentText ||
                "此画面为选片预览，成片由摄影师另行交付。"}
            </p>
          )}
        </div>
        {onPick && (
          <button
            className={current.selected ? "primary" : "secondary"}
            onClick={() => onPick(current)}
          >
            <Heart
              size={18}
              fill={current.selected ? "currentColor" : "none"}
            />
            {current.selected ? "已选中" : "选择这张"}
          </button>
        )}
      </footer>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
