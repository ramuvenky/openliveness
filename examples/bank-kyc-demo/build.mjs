import { build } from 'esbuild';

// Bundles the browser code, the relay url is baked in at build time.
await build({
  entryPoints: ['public/app.ts'],
  bundle: true,
  outfile: 'public/app.js',
  define: { RELAY_URL: JSON.stringify(process.env.RELAY_URL ?? 'http://localhost:3000') },
});
console.log('built public/app.js');
