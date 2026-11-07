// Phase 3 flow: keyword search and autocomplete (ai-search-service, NFR-PERF-02).
import { get, pick, query, think } from '../lib/api.js';

// Common, rare, accent-free and slightly misspelled terms that match the
// Da Nang demo dataset (scripts/seed-demo-data/dataset.json).
export const KEYWORDS = [
  'áo thun', 'ao thun', 'váy', 'quần jean', 'giày', 'giay the thao', 'túi xách',
  'nồi cơm', 'quạt', 'tai nghe', 'sạc', 'gạo', 'cà phê', 'ca phe', 'nước mắm',
  'bánh', 'trà', 'mỹ phẩm', 'son', 'kem chống nắng', 'đồ chơi', 'sách',
  'ao so mi', 'dep', 'balo', 'dong ho', 'ấm siêu tốc', 'xoong', 'chảo', 'khẩu trang',
];

// Type a keyword the way a user does: suggestions for each prefix from 2 chars,
// then the full search, then sometimes page 2.
export function searchSession() {
  const keyword = pick(KEYWORDS);
  for (let length = 2; length <= keyword.length; length += 1 + Math.floor(Math.random() * 2)) {
    get(`/api/ai/search/suggestions${query({ keyword: keyword.slice(0, length), limit: 8 })}`, {
      endpoint: 'GET /api/ai/search/suggestions',
    });
    think(0.15, 0.4); // keystroke gap
  }
  get(`/api/ai/search${query({ keyword, page: 0, size: 20 })}`, { endpoint: 'GET /api/ai/search' });
  think();
  if (Math.random() < 0.25) {
    get(`/api/ai/search${query({ keyword, page: 1, size: 20 })}`, { endpoint: 'GET /api/ai/search' });
    think();
  }
}
