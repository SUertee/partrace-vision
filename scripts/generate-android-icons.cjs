#!/usr/bin/env node
// Asset tooling only: requires sharp on Node's module search path.
// Reuses the supplied artwork; no drawing or tracing of the logo.
const sharp = require('sharp');
const fs = require('node:fs/promises');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const res = path.join(root, 'android/app/src/main/res');
const source = path.join(root, 'android/core/src/commonMain/composeResources/drawable/station_brand_mark.png');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

function background(size) {
  return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
    <defs><radialGradient id="blue"><stop stop-color="#69C4FF"/><stop offset="1" stop-color="#43A7ED"/></radialGradient></defs>
    <rect width="100%" height="100%" fill="url(#blue)"/>
  </svg>`);
}

async function foreground(size, logoHeight) {
  const mark = await sharp(source).resize({ height: logoHeight }).png().toBuffer();
  const meta = await sharp(mark).metadata();
  return sharp({ create: { width: size, height: size, channels: 4, background: '#00000000' } })
    .composite([{ input: mark, left: Math.floor((size - meta.width) / 2), top: Math.floor((size - meta.height) / 2) }])
    .png().toBuffer();
}

async function monochrome(png) {
  const alpha = await sharp(png).extractChannel('alpha').toBuffer();
  const { width, height } = await sharp(png).metadata();
  return sharp({ create: { width, height, channels: 3, background: '#ffffff' } })
    .joinChannel(alpha).png().toBuffer();
}

async function main() {
  for (const [density, scale] of Object.entries(densities)) {
    const directory = path.join(res, `mipmap-${density}`);
    await fs.mkdir(directory, { recursive: true });
    const adaptiveSize = Math.round(108 * scale);
    const adaptiveMark = await foreground(adaptiveSize, Math.round(54 * scale));
    await fs.writeFile(path.join(directory, 'ic_launcher_foreground.png'), adaptiveMark);
    await fs.writeFile(path.join(directory, 'ic_launcher_monochrome.png'), await monochrome(adaptiveMark));
    await sharp(background(adaptiveSize)).png().toFile(path.join(directory, 'ic_launcher_background.png'));

    // Adaptive launchers show the central 72dp of the 108dp layer.
    // Match that apparent mark size in the 48dp legacy assets.
    const size = Math.round(48 * scale);
    const mark = await foreground(size, Math.round(36 * scale));
    const legacy = await sharp(background(size)).composite([{ input: mark }]).png().toBuffer();
    await fs.writeFile(path.join(directory, 'ic_launcher.png'), legacy);
    const circle = Buffer.from(`<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="white"/></svg>`);
    await sharp(legacy).composite([{ input: circle, blend: 'dest-in' }]).png()
      .toFile(path.join(directory, 'ic_launcher_round.png'));
  }

  // Android 12 splash without an icon background: 288dp, inner safe circle 192dp.
  // This 3x canvas keeps the same safe proportions as the launcher foreground.
  await fs.writeFile(path.join(res, 'drawable/splash_icon.png'), await foreground(864, 432));

  const large = path.join(res, 'mipmap-xxxhdpi');
  const mark = await fs.readFile(path.join(large, 'ic_launcher_foreground.png'));
  const composed = await sharp(background(432)).composite([{ input: mark }]).png().toBuffer();
  const blue = await sharp(composed).extract({ left: 72, top: 72, width: 288, height: 288 }).resize(192, 192).png().toBuffer();
  const monoAlpha = await sharp(path.join(large, 'ic_launcher_monochrome.png')).extract({ left: 72, top: 72, width: 288, height: 288 }).resize(192, 192).extractChannel('alpha').toBuffer();
  const mono = await sharp({ create: { width: 192, height: 192, channels: 3, background: '#1A4C73' } }).joinChannel(monoAlpha).png().toBuffer();
  const circleMask = Buffer.from('<svg width="192" height="192"><circle cx="96" cy="96" r="96" fill="white"/></svg>');
  const squareMask = Buffer.from('<svg width="192" height="192"><rect width="192" height="192" rx="46" fill="white"/></svg>');
  const circle = await sharp(blue).composite([{ input: circleMask, blend: 'dest-in' }]).png().toBuffer();
  const square = await sharp(blue).composite([{ input: squareMask, blend: 'dest-in' }]).png().toBuffer();
  const themed = await sharp({ create: { width: 192, height: 192, channels: 4, background: '#D5EAF9' } })
    .composite([{ input: mono }, { input: circleMask, blend: 'dest-in' }]).png().toBuffer();
  const labels = Buffer.from('<svg width="800" height="300"><g font-family="Helvetica, sans-serif" font-size="16" text-anchor="middle" fill="#345168"><text x="144" y="265">Adaptive / Circle</text><text x="400" y="265">Adaptive / Rounded</text><text x="656" y="265">Themed / Monochrome</text></g></svg>');
  const preview = path.join(root, 'android/design/rendered/launcher-icons.png');
  await sharp({ create: { width: 800, height: 300, channels: 4, background: '#F5F8FC' } })
    .composite([{ input: circle, left: 48, top: 32 }, { input: square, left: 304, top: 32 }, { input: themed, left: 560, top: 32 }, { input: labels }])
    .png().toFile(preview);
  console.log('Generated 25 launcher assets, splash artwork, and launcher preview.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
