// In-memory store: the one the tests use, and the fallback when no DATABASE_URL is set.
// PrismaStore (./prisma.js) implements the same methods on PostgreSQL. Every method is async
// so the two are interchangeable.

const copy = (value) => (value == null ? value : structuredClone(value));

export class MemoryStore {
  #sos = new Map();
  #sightings = [];
  #cancels = new Map();
  #acks = new Map();
  #devices = new Map();
  #operators = new Map();

  async getSos(id) {
    return copy(this.#sos.get(id));
  }

  async createSos(alert) {
    this.#sos.set(alert.id, copy(alert));
  }

  async updateSos(id, patch) {
    const current = this.#sos.get(id);
    if (!current) return null;
    const next = { ...current, ...patch };
    this.#sos.set(id, next);
    return copy(next);
  }

  /** Newest first; the service does the final ordering. */
  async listSos({ status, category, since, includeTest } = {}) {
    return [...this.#sos.values()]
      .filter((s) => (!status || s.status === status) && (!category || s.category === category))
      .filter((s) => (since == null || s.createdAt >= since) && (includeTest || !s.isTest))
      .sort((a, b) => b.createdAt - a.createdAt)
      .map(copy);
  }

  async addSighting(sighting) {
    // Postgres enforces this with a foreign key; failing here too keeps the tests honest.
    if (!this.#sos.has(sighting.sosId)) throw new Error(`Sighting for unknown SOS ${sighting.sosId}`);
    this.#sightings.push(copy(sighting));
  }

  async sightingsFor(sosIds) {
    const ids = new Set(sosIds);
    return this.#sightings.filter((s) => ids.has(s.sosId)).map(copy);
  }

  async getCancelFor(sosId) {
    return copy(this.#cancels.get(sosId));
  }

  async putCancel(cancel) {
    if (!this.#cancels.has(cancel.ref)) this.#cancels.set(cancel.ref, copy(cancel));
  }

  async getAck(id) {
    return copy(this.#acks.get(id));
  }

  async addAck(ack) {
    this.#acks.set(ack.id, copy(ack));
  }

  async acksFor(sosIds, kind) {
    const ids = new Set(sosIds);
    return [...this.#acks.values()]
      .filter((a) => ids.has(a.sosId) && (!kind || a.kind === kind))
      .sort((a, b) => a.createdAt - b.createdAt)
      .map(copy);
  }

  async getDevice(publicKey) {
    return copy(this.#devices.get(publicKey));
  }

  async devicesFor(publicKeys) {
    return publicKeys.map((pk) => this.#devices.get(pk)).filter(Boolean).map(copy);
  }

  async putDevice(device) {
    this.#devices.set(device.publicKey, copy(device));
  }

  async findOperatorByEmail(email) {
    return copy([...this.#operators.values()].find((o) => o.email === email));
  }

  async getOperator(id) {
    return copy(this.#operators.get(id));
  }

  async addOperator(operator) {
    this.#operators.set(operator.id, copy(operator));
  }

  async countOperators() {
    return this.#operators.size;
  }
}
