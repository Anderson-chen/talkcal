// 設計稿用到的圖示，path 原樣搬自設計稿。fill 的畫成實心，其餘是線條；顏色都跟著 currentColor。

export interface IconShape {
  fill?: boolean
  paths: string[]
  // 少數圖示除了 path 還有圓形、方形
  circles?: { cx: number; cy: number; r: number }[]
  rect?: boolean
}

export const ICONS = {
  calendar: { paths: ['M4 10h16M9 3v4M15 3v4'], rect: true },
  chevronLeft: { paths: ['M15 18l-6-6 6-6'] },
  chevronRight: { paths: ['M9 18l6-6-6-6'] },
  plus: { paths: ['M12 5v14M5 12h14'] },
  close: { paths: ['M6 6l12 12M18 6L6 18'] },
  sparkle: { fill: true, paths: ['M11 2.5l1.9 5.1 5.1 1.9-5.1 1.9L11 16.5l-1.9-5.1L4 9.5l5.1-1.9z', 'M18.5 14l.9 2.4 2.4.9-2.4.9-.9 2.4-.9-2.4-2.4-.9 2.4-.9z'] },
  clock: { paths: ['M12 7v5l3 2'], circles: [{ cx: 12, cy: 12, r: 9 }] },
  pin: { paths: ['M12 21s-7-6.2-7-11.5a7 7 0 0114 0C19 14.8 12 21 12 21z'], circles: [{ cx: 12, cy: 9.5, r: 2.5 }] },
  note: { paths: ['M5 4h14v16H5z', 'M9 9h6M9 13h6M9 17h3'] },
  palette: {
    paths: ['M12 3a9 9 0 100 18c1.1 0 1.6-.8 1.6-1.6 0-.9-.7-1.3-.7-2.1 0-.9.7-1.6 1.6-1.6H16a5 5 0 005-5C21 6.5 17 3 12 3z'],
    circles: [{ cx: 7.5, cy: 11.5, r: 1 }, { cx: 10, cy: 7.5, r: 1 }, { cx: 15, cy: 7.5, r: 1 }],
  },
  trash: { paths: ['M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3'] },
  send: { paths: ['M12 19V5M5 12l7-7 7 7'] },
  edit: { paths: ['M4 20h4L19 9l-4-4L4 16z', 'M13 7l4 4'] },
} satisfies Record<string, IconShape>

export type IconName = keyof typeof ICONS
