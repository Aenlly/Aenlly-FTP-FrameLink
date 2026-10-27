export type Media = {
  id: string;
  name: string;
  kind: "PREVIEW" | "DELIVERY";
  parentId?: string;
  mime: string;
  bytes: number;
  selected: boolean;
  commentText: string;
  sourceKey?: string;
};
export type Choice = { mediaId: string; selected: boolean; comment: string };
export type Revision = {
  id: string;
  roundNo: number;
  submittedAt: number;
  items: (Choice & { name: string; commentText: string })[];
};
export type Project = {
  id: string;
  name: string;
  state: "DRAFT" | "SHARED" | "SUBMITTED" | "CONFIRMED" | "DELIVERED";
  roundNo: number;
  selectionVersion: number;
  updatedAt: number;
  createdAt: number;
  mediaCount?: number;
  media?: Media[];
  shares?: Share[];
  revisions?: Revision[];
  allowDownload?: boolean;
  expiresAt?: number;
};
export type Share = {
  id: string;
  revoked: boolean;
  allowDownload: boolean;
  expiresAt: number;
  url?: string;
  accessCode?: string;
};
export type Account = {
  id: string;
  email: string;
  quotaBytes: number;
  usedBytes: number;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  token: string,
  options: Omit<RequestInit, "body"> & { body?: unknown } = {},
): Promise<T> {
  const body =
    options.body instanceof FormData
      ? options.body
      : options.body === undefined
        ? undefined
        : JSON.stringify(options.body);
  const response = await fetch("/api" + path, {
    ...options,
    body,
    headers: {
      ...(body && !(body instanceof FormData)
        ? { "Content-Type": "application/json" }
        : {}),
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...options.headers,
    },
  });
  if (!response.ok) {
    const problem = await response
      .json()
      .catch(() => ({ message: "网络请求失败" }));
    throw new ApiError(response.status, problem.message || "操作未完成");
  }
  return response.json();
}
export async function download(path: string, token: string, name: string) {
  const response = await fetch("/api" + path, {
    headers: { Authorization: "Bearer " + token },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: "下载失败" }));
    throw new ApiError(response.status, error.message);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const stateName: Record<Project["state"], string> = {
  DRAFT: "准备照片",
  SHARED: "客户选片中",
  SUBMITTED: "等待确认",
  CONFIRMED: "选片已确认",
  DELIVERED: "成片已交付",
};
export function bytes(size: number) {
  if (size < 1024) return size + " B";
  if (size < 1024 ** 2) return (size / 1024).toFixed(0) + " KB";
  if (size < 1024 ** 3) return (size / 1024 ** 2).toFixed(1) + " MB";
  return (size / 1024 ** 3).toFixed(1) + " GB";
}
