import { Hono } from "hono";
import { logger } from "hono/logger";
import { env } from "@/env";
import { basket } from "@/routes/basket";
import { basketsRoute } from "@/routes/baskets";
import { recipes } from "@/routes/recipes";
import { purchasesRoute } from "@/routes/purchases";
import { share } from "@/routes/share";
import { whatsapp } from "@/routes/whatsapp";
import { chatRoute } from "@/routes/chat";
import { meta } from "@/routes/meta";

function constantTimeEqual(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function createApp(options: { token?: string; log?: boolean } = {}) {
  const token = options.token ?? env.apiToken;
  const app = new Hono();
  if (options.log ?? true) app.use(logger());

  app.get("/", (c) => c.json({ name: "baskt", docs: "/api/v1/health" }));
  // Share pages authenticate with the basket's own token instead of the app bearer.
  app.route("/share", share);

  app.use("/api/v1/*", async (c, next) => {
    if (!token) return next();
    const header = c.req.header("authorization") ?? "";
    const provided = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (!constantTimeEqual(provided, token)) return c.json({ error: { code: "UNAUTHORIZED", message: "invalid bearer token" } }, 401);
    return next();
  });

  app.route("/api/v1", meta);
  app.route("/api/v1/basket", basket);
  app.route("/api/v1/baskets", basketsRoute);
  app.route("/api/v1/recipes", recipes);
  app.route("/api/v1/purchases", purchasesRoute);
  app.route("/api/v1/whatsapp", whatsapp);
  app.route("/api/v1/chat", chatRoute);

  app.notFound((c) => c.json({ error: { code: "NOT_FOUND", message: `no route for ${c.req.method} ${c.req.path}` } }, 404));
  app.onError((error, c) => {
    console.error(error);
    return c.json({ error: { code: "INTERNAL", message: error.message } }, 500);
  });
  return app;
}
