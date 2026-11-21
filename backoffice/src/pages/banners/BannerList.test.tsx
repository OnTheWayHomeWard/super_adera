import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { BannerView } from "../../lib/banners";
import { setIdentity } from "../../lib/identity";
import { BannerPreview } from "./BannerPreview";
import { StateBadge, StatusButtons } from "./BannerList";

const draft = { id: "b1", status: "DRAFT", imageId: null, state: "DRAFT", visible: false, app: null } as unknown as BannerView;
const live = { ...draft, status: "PUBLISHED", imageId: "i1", state: "LIVE", visible: true } as BannerView;
const noop = async () => undefined;

afterEach(() => act(() => setIdentity(null)));

describe("banner status buttons follow the role", () => {
  it("a VIEWER gets no write buttons", () => {
    act(() => setIdentity({ id: "s", role: "VIEWER" }));
    render(<StatusButtons banner={live} onChanged={noop} />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("an ADMIN can publish only once there is an image", () => {
    act(() => setIdentity({ id: "s", role: "ADMIN" }));
    const { unmount } = render(<StatusButtons banner={draft} onChanged={noop} />);
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Pause" })).toBeNull();
    unmount();
    render(<StatusButtons banner={live} onChanged={noop} />);
    expect(screen.getByRole("button", { name: "Pause" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Back to draft" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Publish" })).toBeNull();
  });
});

describe("state badge and preview", () => {
  it("marks a live banner of a paused app as not shown", () => {
    render(<StateBadge banner={{ ...live, visible: false, app: { id: "a", slug: "demo-ride", name: { en: "Ride" }, logoUrl: null, status: "PAUSED" } }} />);
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("not shown")).toBeInTheDocument();
  });

  it("shows the title in the chosen language and the category", () => {
    render(<BannerPreview placement="FEATURED" category="EVENT" title={{ en: "Concert", am: "ኮንሰርት" }} subtitle={{}} imageUrl={null} lang="am" />);
    expect(screen.getByText("ኮንሰርት")).toBeInTheDocument();
    expect(screen.getByText("Event")).toBeInTheDocument();
  });
});
