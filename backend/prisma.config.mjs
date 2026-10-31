// Prisma 7 reads the database URL from here, not from the schema.
import { defineConfig } from 'prisma/config';

// Prisma 7 no longer reads .env itself; load it here when present (on Render the variables are set directly).
try {
  process.loadEnvFile('.env');
} catch {
  // no .env file
}

export default defineConfig({
  schema: 'prisma/schema.prisma',
  migrations: { path: 'prisma/migrations' },
  datasource: { url: process.env.DATABASE_URL ?? '' },
});
