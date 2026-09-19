import { index, integer, primaryKey, real, sqliteTable, text, uniqueIndex, type AnySQLiteColumn } from "drizzle-orm/sqlite-core";

export const products = sqliteTable(
  "products",
  {
    id: text("id").primaryKey(), // `${store}:${sourceId}`
    store: text("store").notNull(),
    sourceId: text("source_id").notNull(),
    title: text("title").notNull(),
    brand: text("brand"),
    quantityText: text("quantity_text").notNull(),
    unitAmount: real("unit_amount"),
    unit: text("unit"),
    priceCents: integer("price_cents").notNull(),
    regularPriceCents: integer("regular_price_cents"),
    unitPriceCents: integer("unit_price_cents"),
    unitPriceUnit: text("unit_price_unit"),
    dealText: text("deal_text"),
    isDeal: integer("is_deal", { mode: "boolean" }).notNull().default(false),
    imageUrl: text("image_url"),
    sourceUrl: text("source_url"),
    category: text("category"),
    available: integer("available", { mode: "boolean" }).notNull().default(true),
    fetchedAt: integer("fetched_at").notNull(),
  },
  (table) => [index("products_store_idx").on(table.store), index("products_title_idx").on(table.title)],
);

export const priceHistory = sqliteTable(
  "price_history",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    productId: text("product_id").notNull(),
    priceCents: integer("price_cents").notNull(),
    isDeal: integer("is_deal", { mode: "boolean" }).notNull().default(false),
    capturedAt: integer("captured_at").notNull(),
  },
  (table) => [index("price_history_product_idx").on(table.productId, table.capturedAt)],
);

export const searchCache = sqliteTable(
  "search_cache",
  {
    store: text("store").notNull(),
    normalizedQuery: text("normalized_query").notNull(),
    productIds: text("product_ids", { mode: "json" }).$type<string[]>().notNull(),
    fetchedAt: integer("fetched_at").notNull(),
    expiresAt: integer("expires_at").notNull(),
  },
  (table) => [primaryKey({ columns: [table.store, table.normalizedQuery] })],
);

export const DEFAULT_BASKET_ID = "default";

/** Lists the user can switch between (Personal, Family, Sweets, ...). */
export const baskets = sqliteTable("baskets", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  emoji: text("emoji"),
  sortOrder: integer("sort_order").notNull().default(0),
  /** Secret in the share link; anyone with it can view and check items of this basket in a browser. */
  shareToken: text("share_token"),
  createdAt: integer("created_at").notNull(),
  updatedAt: integer("updated_at").notNull(),
});

/** Things the user already has at home; recipe folders skip them. */
export const stock = sqliteTable("stock", {
  id: text("id").primaryKey(),
  text: text("text").notNull(),
  canonical: text("canonical").notNull(),
  quantityText: text("quantity_text"),
  /** Set when the entry came from a scanned/picked product. */
  productId: text("product_id"),
  imageUrl: text("image_url"),
  barcode: text("barcode"),
  addedAt: integer("added_at").notNull(),
  updatedAt: integer("updated_at").notNull(),
});

/** Saved recipes from the meal browser. */
export const recipeFavourites = sqliteTable("recipe_favourites", {
  id: text("id").primaryKey(),
  title: text("title").notNull(),
  url: text("url").notNull(),
  imageUrl: text("image_url"),
  createdAt: integer("created_at").notNull(),
});

/** The user's own recipes: written by hand, drafted by the AI, or copied from a site. */
export const userRecipes = sqliteTable("user_recipes", {
  id: text("id").primaryKey(),
  title: text("title").notNull(),
  description: text("description"),
  servings: text("servings"),
  ingredientLines: text("ingredient_lines", { mode: "json" }).$type<string[]>().notNull(),
  steps: text("steps", { mode: "json" }).$type<Array<{ text: string; imageUrl: string | null }>>().notNull(),
  imageUrl: text("image_url"),
  sourceUrl: text("source_url"),
  /** "manual" | "ai" | "site" */
  origin: text("origin").notNull().default("manual"),
  createdAt: integer("created_at").notNull(),
  updatedAt: integer("updated_at").notNull(),
});

/** Real purchases, from scanned receipts (or manual entry). */
export const purchases = sqliteTable("purchases", {
  id: text("id").primaryKey(),
  store: text("store").notNull(),
  purchasedAt: integer("purchased_at").notNull(),
  totalCents: integer("total_cents").notNull(),
  source: text("source").notNull().default("receipt"),
  createdAt: integer("created_at").notNull(),
});

export const purchaseLines = sqliteTable(
  "purchase_lines",
  {
    id: text("id").primaryKey(),
    purchaseId: text("purchase_id").notNull(),
    name: text("name").notNull(),
    productId: text("product_id"),
    quantity: real("quantity").notNull().default(1),
    unitPriceCents: integer("unit_price_cents"),
    totalPriceCents: integer("total_price_cents").notNull(),
    dealText: text("deal_text"),
    sortOrder: integer("sort_order").notNull().default(0),
  },
  (table) => [index("purchase_lines_purchase_idx").on(table.purchaseId)],
);

export type ItemStatus = "NEW" | "PARSING" | "MATCHING" | "MATCHED" | "ERROR";
export type ItemKind = "item" | "group";

export type RecipeInfo = {
  title: string;
  sourceUrl: string | null;
  servings: string | null;
  ingredientLines: string[];
  /** Ingredients left out because they are in stock (can be added later from the folder). */
  skipped?: Array<{ text: string; quantity: number; reason: string }>;
  /** Servings the recipe was written for, and the servings the folder is currently scaled to. */
  baseServings?: number | null;
  currentServings?: number | null;
  imageUrl?: string | null;
  steps?: Array<{ text: string; imageUrl: string | null }>;
};

export const basketItems = sqliteTable("basket_items", {
  id: text("id").primaryKey(),
  basketId: text("basket_id").notNull().default(DEFAULT_BASKET_ID),
  /** "item" = a shopping idea with matches; "group" = a folder (e.g. a recipe) holding child items. */
  kind: text("kind").$type<ItemKind>().notNull().default("item"),
  parentId: text("parent_id").references((): AnySQLiteColumn => basketItems.id, { onDelete: "cascade" }),
  recipeJson: text("recipe_json", { mode: "json" }).$type<RecipeInfo | null>(),
  /** Store this item will be bought at (set in the order step); null = not decided yet. */
  assignedStore: text("assigned_store"),
  /** Why the item was created already checked, e.g. "in stock: zout". */
  skippedReason: text("skipped_reason"),
  text: text("text").notNull(),
  quantity: integer("quantity").notNull().default(1),
  checked: integer("checked", { mode: "boolean" }).notNull().default(false),
  sortOrder: integer("sort_order").notNull().default(0),
  status: text("status").$type<ItemStatus>().notNull().default("NEW"),
  error: text("error"),
  parsedJson: text("parsed_json", { mode: "json" }).$type<ParsedIdea | null>(),
  createdAt: integer("created_at").notNull(),
  updatedAt: integer("updated_at").notNull(),
});

/** Wordings the user asked for ("krulsla melange" should read "bolsa de lechugas"); tidy and translations follow them. */
export const namingMemory = sqliteTable(
  "naming_memory",
  {
    id: text("id").primaryKey(),
    /** What the entry said before, as written. */
    source: text("source").notNull(),
    /** Normalized lookups: the source text and its parsed generic name. */
    keys: text("keys", { mode: "json" }).$type<string[]>().notNull(),
    preferred: text("preferred").notNull(),
    language: text("language").notNull(),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [index("naming_memory_preferred_idx").on(table.preferred)],
);

export type MatchStatus = "PENDING" | "CHOSEN" | "NONE" | "EXHAUSTED";
export type ChosenBy = "USER" | "MEMORY" | "AI";

export const basketMatches = sqliteTable(
  "basket_matches",
  {
    id: text("id").primaryKey(),
    itemId: text("item_id")
      .notNull()
      .references(() => basketItems.id, { onDelete: "cascade" }),
    store: text("store").notNull(),
    candidateIds: text("candidate_ids", { mode: "json" }).$type<string[]>().notNull(),
    equivalences: text("equivalences", { mode: "json" }).$type<Record<string, Equivalence>>().notNull(),
    hasRejectedSuggestions: integer("has_rejected_suggestions", { mode: "boolean" }).notNull().default(false),
    windowStart: integer("window_start").notNull().default(0),
    shownCount: integer("shown_count").notNull().default(3),
    chosenProductId: text("chosen_product_id"),
    status: text("status").$type<MatchStatus>().notNull().default("PENDING"),
    chosenBy: text("chosen_by").$type<ChosenBy | null>(),
    confidence: real("confidence"),
    reason: text("reason"),
    updatedAt: integer("updated_at").notNull(),
  },
  (table) => [uniqueIndex("basket_matches_item_store").on(table.itemId, table.store)],
);

export const choices = sqliteTable(
  "choices",
  {
    id: text("id").primaryKey(),
    itemText: text("item_text").notNull(),
    canonical: text("canonical").notNull(),
    store: text("store").notNull(),
    chosenProductId: text("chosen_product_id"),
    chosenTitle: text("chosen_title"),
    rejectedTitles: text("rejected_titles", { mode: "json" }).$type<string[]>().notNull(),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [index("choices_canonical_idx").on(table.canonical)],
);

/** Assistant chat, one thread per basket. Assistant messages may carry a proposal (pending changes) or recipe cards. */
export const chatMessages = sqliteTable(
  "chat_messages",
  {
    id: text("id").primaryKey(),
    basketId: text("basket_id").notNull(),
    role: text("role").notNull(), // user | assistant
    content: text("content").notNull(),
    proposalJson: text("proposal_json", { mode: "json" }).$type<Proposal | null>(),
    recipesJson: text("recipes_json", { mode: "json" }).$type<RecipeCard[] | null>(),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [index("chat_messages_basket_idx").on(table.basketId, table.createdAt)],
);

export type ProposedChange =
  | { type: "add"; text: string; quantity: number; inStock?: boolean; stockName?: string | null; inList?: string | null }
  | { type: "delete"; itemId: string; text: string }
  | { type: "rename"; itemId: string; from: string; to: string }
  | { type: "quantity"; itemId: string; text: string; quantity: number }
  | { type: "replace"; itemId: string; text: string; store: string; productId: string; from: string | null; to: string }
  | { type: "skip"; itemId: string; text: string; store: string }
  | { type: "add_recipe_folder"; url: string; title: string };

export type Proposal = { summary: string; changes: ProposedChange[]; applied: number[] | null };
export type RecipeCard = { title: string; url: string; source: string | null; imageUrl: string | null };
export type ChatMessageRow = typeof chatMessages.$inferSelect;

/** Thumbs up/down on a "find similar" result: which product is (not) a stand-in for the reference. */
export const similarFeedback = sqliteTable(
  "similar_feedback",
  {
    referenceProductId: text("reference_product_id").notNull(),
    productId: text("product_id").notNull(),
    up: integer("up", { mode: "boolean" }).notNull(),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [primaryKey({ columns: [table.referenceProductId, table.productId] })],
);
export type SimilarFeedbackRow = typeof similarFeedback.$inferSelect;

export const aiCache = sqliteTable(
  "ai_cache",
  {
    kind: text("kind").notNull(),
    key: text("key").notNull(),
    responseJson: text("response_json").notNull(),
    createdAt: integer("created_at").notNull(),
  },
  (table) => [primaryKey({ columns: [table.kind, table.key] })],
);

export const settings = sqliteTable("settings", {
  key: text("key").primaryKey(),
  valueJson: text("value_json").notNull(),
});

export const tombstones = sqliteTable("tombstones", {
  collection: text("collection").notNull(),
  entityId: text("entity_id").notNull(),
  deletedAt: integer("deleted_at").notNull(),
}, (table) => [primaryKey({ columns: [table.collection, table.entityId] })]);

export type Equivalence = "EXACT" | "EQUIVALENT" | "SUBSTITUTE";

export type ParsedIdea = {
  canonicalName: string;
  attributes: string[];
  sizeHint: { amount: number; unit: string } | null;
  queries: Record<string, string>;
  /** Generic Dutch product noun used when the store-specific query returns too little. */
  fallbackQuery: string | null;
  ambiguous: boolean;
};

export type BasketRow = typeof baskets.$inferSelect;
export type StockRow = typeof stock.$inferSelect;
export type UserRecipeRow = typeof userRecipes.$inferSelect;
export type PurchaseRow = typeof purchases.$inferSelect;
export type PurchaseLineRow = typeof purchaseLines.$inferSelect;
export type ProductRow = typeof products.$inferSelect;
export type BasketItemRow = typeof basketItems.$inferSelect;
export type BasketMatchRow = typeof basketMatches.$inferSelect;
export type ChoiceRow = typeof choices.$inferSelect;
export type NamingMemoryRow = typeof namingMemory.$inferSelect;

/** Shared upstream cache; safe to prune by expiry. */
export const upstreamCache = sqliteTable("upstream_cache", {
  key: text("key").primaryKey(),
  value: text("value", { mode: "json" }).$type<unknown>().notNull(),
  expiresAt: integer("expires_at").notNull(),
  staleUntil: integer("stale_until").notNull(),
});

/** Successful outbox responses survive reconnects and server restarts. */
export const mutationReceipts = sqliteTable("mutation_receipts", {
  key: text("key").primaryKey(),
  fingerprint: text("fingerprint").notNull(),
  status: integer("status").notNull(),
  body: text("body").notNull(),
  createdAt: integer("created_at").notNull(),
});
