// PostgreSQL store (Neon in production) with the same methods as MemoryStore. Times are BigInt
// columns in the database and plain numbers everywhere else, converted at this boundary.

import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '@prisma/client';

const TIME_FIELDS = ['createdAt', 'expiresAt', 'firstReceivedAt', 'cancelledAt', 'updatedAt', 'receivedAt', 'ts', 'registeredAt', 'lastSeenAt'];

const toDb = (row) => {
  const out = { ...row };
  for (const f of TIME_FIELDS) if (out[f] != null) out[f] = BigInt(out[f]);
  return out;
};

const fromDb = (row) => {
  if (!row) return row;
  const out = { ...row };
  for (const f of TIME_FIELDS) if (typeof out[f] === 'bigint') out[f] = Number(out[f]);
  return out;
};

export class PrismaStore {
  constructor(prisma) {
    this.prisma = prisma;
  }

  static async connect(connectionString) {
    const prisma = new PrismaClient({ adapter: new PrismaPg({ connectionString }) });
    await prisma.$connect();
    return new PrismaStore(prisma);
  }

  async getSos(id) {
    return fromDb(await this.prisma.sosAlert.findUnique({ where: { id } }));
  }

  async createSos(alert) {
    await this.prisma.sosAlert.create({ data: toDb(alert) });
  }

  async updateSos(id, patch) {
    try {
      return fromDb(await this.prisma.sosAlert.update({ where: { id }, data: toDb(patch) }));
    } catch (e) {
      if (e.code === 'P2025') return null; // no such row
      throw e;
    }
  }

  async listSos({ status, category, since, includeTest } = {}) {
    const rows = await this.prisma.sosAlert.findMany({
      where: {
        ...(status ? { status } : {}),
        ...(category ? { category } : {}),
        ...(since != null ? { createdAt: { gte: BigInt(since) } } : {}),
        ...(includeTest ? {} : { isTest: false }),
      },
      orderBy: { createdAt: 'desc' },
      take: 500,
    });
    return rows.map(fromDb);
  }

  async addSighting(sighting) {
    await this.prisma.sighting.create({ data: toDb(sighting) });
  }

  async sightingsFor(sosIds) {
    if (sosIds.length === 0) return [];
    const rows = await this.prisma.sighting.findMany({ where: { sosId: { in: sosIds } }, orderBy: { receivedAt: 'asc' } });
    return rows.map(({ id, ...rest }) => fromDb(rest));
  }

  async getCancelFor(sosId) {
    return fromDb(await this.prisma.cancel.findUnique({ where: { ref: sosId } }));
  }

  async putCancel(cancel) {
    // The first cancel for an SOS wins, as in MemoryStore.
    await this.prisma.cancel.upsert({ where: { ref: cancel.ref }, create: toDb(cancel), update: {} });
  }

  async getAck(id) {
    return fromDb(await this.prisma.ack.findUnique({ where: { id } }));
  }

  async addAck(ack) {
    await this.prisma.ack.create({ data: toDb(ack) });
  }

  async acksFor(sosIds, kind) {
    if (sosIds.length === 0) return [];
    const rows = await this.prisma.ack.findMany({
      where: { sosId: { in: sosIds }, ...(kind ? { kind } : {}) },
      orderBy: { createdAt: 'asc' },
    });
    return rows.map(fromDb);
  }

  async getDevice(publicKey) {
    return fromDb(await this.prisma.device.findUnique({ where: { publicKey } }));
  }

  async devicesFor(publicKeys) {
    if (publicKeys.length === 0) return [];
    return (await this.prisma.device.findMany({ where: { publicKey: { in: publicKeys } } })).map(fromDb);
  }

  async putDevice(device) {
    const data = toDb(device);
    await this.prisma.device.upsert({ where: { publicKey: device.publicKey }, create: data, update: data });
  }

  async findOperatorByEmail(email) {
    return this.prisma.operator.findUnique({ where: { email } });
  }

  async getOperator(id) {
    return this.prisma.operator.findUnique({ where: { id } });
  }

  async addOperator(operator) {
    await this.prisma.operator.create({ data: operator });
  }

  async countOperators() {
    return this.prisma.operator.count();
  }
}
