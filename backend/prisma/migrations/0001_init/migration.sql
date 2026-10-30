-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateTable
CREATE TABLE "sos_alerts" (
    "id" TEXT NOT NULL,
    "sender_pk" TEXT NOT NULL,
    "node_id" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "lat" DOUBLE PRECISION,
    "lon" DOUBLE PRECISION,
    "accuracy_m" INTEGER,
    "fix_age_s" INTEGER,
    "people" INTEGER NOT NULL,
    "note" TEXT,
    "battery" INTEGER,
    "lang" TEXT NOT NULL,
    "is_test" BOOLEAN NOT NULL,
    "created_at" BIGINT NOT NULL,
    "expires_at" BIGINT NOT NULL,
    "first_received_at" BIGINT NOT NULL,
    "status" TEXT NOT NULL,
    "cancel_reason" TEXT,
    "cancelled_at" BIGINT,
    "sms_escalated" BOOLEAN NOT NULL,
    "envelope" TEXT NOT NULL,
    "updated_at" BIGINT NOT NULL,

    CONSTRAINT "sos_alerts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sightings" (
    "id" SERIAL NOT NULL,
    "sos_id" TEXT NOT NULL,
    "gateway_node_id" TEXT NOT NULL,
    "hops" INTEGER NOT NULL,
    "received_at" BIGINT NOT NULL,

    CONSTRAINT "sightings_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "cancels" (
    "ref" TEXT NOT NULL,
    "id" TEXT NOT NULL,
    "sender_pk" TEXT NOT NULL,
    "reason" TEXT NOT NULL,
    "ts" BIGINT NOT NULL,

    CONSTRAINT "cancels_pkey" PRIMARY KEY ("ref")
);

-- CreateTable
CREATE TABLE "acks" (
    "id" TEXT NOT NULL,
    "sos_id" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "signer_pk" TEXT NOT NULL,
    "code" TEXT,
    "message" TEXT,
    "unit" TEXT,
    "uploaded" BOOLEAN,
    "sms" BOOLEAN,
    "gateway_node_id" TEXT,
    "operator_id" TEXT,
    "created_at" BIGINT NOT NULL,
    "envelope" TEXT NOT NULL,

    CONSTRAINT "acks_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "devices" (
    "public_key" TEXT NOT NULL,
    "node_id" TEXT NOT NULL,
    "display_name" TEXT,
    "phone" TEXT,
    "registered_at" BIGINT NOT NULL,
    "last_seen_at" BIGINT NOT NULL,

    CONSTRAINT "devices_pkey" PRIMARY KEY ("public_key")
);

-- CreateTable
CREATE TABLE "operators" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "password_hash" TEXT NOT NULL,
    "name" TEXT NOT NULL,

    CONSTRAINT "operators_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "sos_alerts_status_created_at_idx" ON "sos_alerts"("status", "created_at");

-- CreateIndex
CREATE INDEX "sightings_sos_id_idx" ON "sightings"("sos_id");

-- CreateIndex
CREATE INDEX "acks_sos_id_idx" ON "acks"("sos_id");

-- CreateIndex
CREATE UNIQUE INDEX "operators_email_key" ON "operators"("email");

-- AddForeignKey
ALTER TABLE "sightings" ADD CONSTRAINT "sightings_sos_id_fkey" FOREIGN KEY ("sos_id") REFERENCES "sos_alerts"("id") ON DELETE CASCADE ON UPDATE CASCADE;

