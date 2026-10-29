import { defineConfig } from 'vitest/config';

// One worker keeps Angular's DOM setup and the running local demo within laptop memory.
export default defineConfig({ test: { maxWorkers: 1, fileParallelism: false, testTimeout: 15000, hookTimeout: 30000 } });
