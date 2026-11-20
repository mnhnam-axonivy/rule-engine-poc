/**
 * The icons of the Process Designer of Axon Ivy that the simulation shows as they are: the callable start and end of
 * a callable sub process, and the Business Rule Task. Both the process canvas and the inscription header draw them.
 */

/** Callable start `price(Invoice)`: an arrow that comes in from the top left and points down. Filled, 20 × 20. */
export const SUB_START_PATH = 'M3.85303 2C3.85303 1.72386 4.07688 1.5 4.35303 1.5H9.00009C9.82851 1.5 10.5001 2.17157 10.5001 3V16.7929'
  + 'L15.2936 11.9994C15.4889 11.8041 15.8054 11.8041 16.0007 11.9994C16.196 12.1947 16.196 12.5112 16.0007 12.7065'
  + 'L10.3536 18.3536C10.2599 18.4473 10.1327 18.5 10.0001 18.5C9.86748 18.5 9.7403 18.4473 9.64653 18.3536'
  + 'L3.99947 12.7065C3.80421 12.5112 3.80421 12.1946 3.99947 11.9994C4.19474 11.8041 4.51132 11.8041 4.70658 11.9994'
  + 'L9.50009 16.7929V3C9.50009 2.72386 9.27623 2.5 9.00009 2.5H4.35303C4.07688 2.5 3.85303 2.27614 3.85303 2Z';

/** Callable end: an arrow that points up and leaves to the bottom right. Filled, 20 × 20. */
export const SUB_END_PATH = 'M10.5001 1.5C10.6327 1.5 10.7599 1.55268 10.8536 1.64645L16.5007 7.29351C16.696 7.48877 16.696 7.80535 16.5007 8.00061'
  + 'C16.3054 8.19588 15.9889 8.19588 15.7936 8.00061L11.0001 3.20711L11.0001 17C11.0001 17.2761 11.2239 17.5 11.5001 17.5'
  + 'H16.1471C16.4233 17.5 16.6471 17.7239 16.6471 18C16.6471 18.2761 16.4233 18.5 16.1471 18.5H11.5001'
  + 'C10.6717 18.5 10.0001 17.8284 10.0001 17L10.0001 3.20711L5.20658 8.00061C5.01132 8.19587 4.69474 8.19587 4.49947 8.00061'
  + 'C4.30421 7.80535 4.30421 7.48877 4.49947 7.29351L10.1465 1.64645C10.2403 1.55268 10.3675 1.5 10.5001 1.5Z';

/** The icon of a callable start or end, by element type; null for every other element. */
export function subEventPath(type) {
  return type === 'CallSubStart' ? SUB_START_PATH : type === 'CallSubEnd' ? SUB_END_PATH : null;
}

/**
 * The icon of the Business Rule Task (`rule-engine/webContent/logo/business-rule-task.png`), 64 × 64: a blue tile
 * with a table, a yellow header and three rows of three cells.
 */
export const RULE_TILE = {
  size: 64,
  radius: 12,
  tile: '#1b5e96',
  header: { x: 12, y: 14, w: 40, h: 9, color: '#ffc440' },
  cells: { xs: [13, 26, 39], w: 12, rows: [[24, 8], [33, 8], [42, 7]], color: '#ffffff' },
  /** the box the Process Designer puts the icon on */
  background: '#c3eddf'
};

/** The rule tile as SVG markup, for the inscription header. */
export function ruleTileSvg(size) {
  const t = RULE_TILE;
  const cells = t.cells.rows.flatMap(([y, h]) => t.cells.xs.map(x =>
    '<rect x="' + x + '" y="' + y + '" width="' + t.cells.w + '" height="' + h + '"/>')).join('');
  return '<svg class="icon" viewBox="0 0 64 64" width="' + size + '" height="' + size + '" aria-hidden="true">'
    + '<rect width="64" height="64" rx="' + t.radius + '" fill="' + t.tile + '"/>'
    + '<rect x="' + t.header.x + '" y="' + t.header.y + '" width="' + t.header.w + '" height="' + t.header.h + '" fill="' + t.header.color + '"/>'
    + '<g fill="' + t.cells.color + '">' + cells + '</g></svg>';
}
