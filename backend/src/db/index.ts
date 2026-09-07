import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { migrate } from "drizzle-orm/bun-sqlite/migrator";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { env } from "@/env";
import * as schema from "@/db/schema";

export type Db = ReturnType<typeof createDb>;

export function createDb(path: string) {
  if (path !== ":memory:") {
    mkdirSync(dirname(resolve(path)), { recursive: true });
  }
  const sqlite = new Database(path, { create: true });
  sqlite.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  const db = drizzle(sqlite, { schema });
  migrate(db, { migrationsFolder: resolve(import.meta.dir, "../../drizzle") });
  ensureDefaultBasket(db);
  return db;
}

function ensureDefaultBasket(db: ReturnType<typeof drizzle>) {
  const existing = db.select({ id: schema.baskets.id }).from(schema.baskets).all();
  if (!existing.length) {
    db.insert(schema.baskets).values({ id: schema.DEFAULT_BASKET_ID, name: "Personal", emoji: "🧺", sortOrder: 0, createdAt: Date.now(), updatedAt: Date.now() }).run();
  }
}

let instance: Db | null = null;

export function db() {
  if (!instance) {
    instance = createDb(env.databasePath);
  }
  return instance;
}

export function resetDbForTests(path = ":memory:") {
  instance = createDb(path);
  return instance;
}

export const now = () => Date.now();
export const newId = () => crypto.randomUUID();
