import { Hono, type Context } from "hono";
import { autoActivate } from "@/matching/personal-deals";
import { HttpError } from "@/lib/http";
import { accountStatus, activateOffer, AhAccountError, connect, disconnect, personalOffers, setAutoActivate, type PersonalOffersView } from "@/stores/ah-account";
import { productsByIds } from "@/stores/search";

/** The user's Albert Heijn account: login, personal Bonus offers and their activation. */
export const ahRoute = new Hono();

function failure(c: Context, error: unknown) {
  if (error instanceof AhAccountError) return c.json({ error: { code: error.code === "AH_ERROR" ? "AH_ERROR" : `AH_${error.code}`, message: error.message } }, error.code === "AH_ERROR" ? 502 : 409);
  if (error instanceof HttpError) return c.json({ error: { code: "AH_ERROR", message: `AH replied ${error.status}` } }, 502);
  throw error;
}

/** The offers plus the catalogue rows of their products, so the app can show and add them. */
function offersBody(view: PersonalOffersView) {
  const ids = Array.from(new Set(view.offers.flatMap((offer) => offer.productIds.slice(0, 12))));
  return { ...view, products: Object.fromEntries(productsByIds(ids).map((row) => [row.id, row])) };
}

ahRoute.get("/account", (c) => c.json(accountStatus()));

ahRoute.post("/account/connect", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { code?: string };
  if (!body.code?.trim()) return c.json({ error: { code: "BAD_REQUEST", message: "code is required" } }, 400);
  try {
    const status = await connect(body.code);
    void personalOffers({ force: true }).then(() => autoActivate({ force: true })).catch(() => {});
    return c.json(status);
  } catch (error) {
    if (error instanceof HttpError) return c.json({ error: { code: "AH_LOGIN_FAILED", message: "AH did not accept the login, try again" } }, 400);
    return failure(c, error);
  }
});

ahRoute.patch("/account", async (c) => {
  const body = (await c.req.json().catch(() => ({}))) as { autoActivate?: boolean };
  if (typeof body.autoActivate === "boolean") setAutoActivate(body.autoActivate);
  return c.json(accountStatus());
});

ahRoute.delete("/account", (c) => {
  disconnect();
  return c.json(accountStatus());
});

ahRoute.get("/offers", async (c) => {
  try {
    return c.json(offersBody(await personalOffers({ force: c.req.query("force") === "true" })));
  } catch (error) {
    return failure(c, error);
  }
});

ahRoute.post("/offers/:id/activate", async (c) => {
  try {
    const result = await activateOffer(c.req.param("id"));
    return c.json({ status: result.status, message: result.message, offers: offersBody(result.offers) });
  } catch (error) {
    return failure(c, error);
  }
});

/** Runs auto-activation now (it also runs hourly and after login). */
ahRoute.post("/offers/auto-activate", async (c) => {
  try {
    const activated = await autoActivate({ force: true });
    return c.json({ activated, offers: offersBody(await personalOffers()) });
  } catch (error) {
    return failure(c, error);
  }
});
