import { defaultTitle, normalizeUrl } from './normalize-url';

describe('normalizeUrl', () => {
  it('keeps the link as saved and derives a comparison form without tracking or fragment', () => {
    const result = normalizeUrl(
      '  https://WWW.Example.com/a/My.Video.mp4?utm_source=wa&sig=abc&fbclid=1#t=30 ',
    );

    expect(result).toMatchObject({
      sourceUrl: 'https://WWW.Example.com/a/My.Video.mp4?utm_source=wa&sig=abc&fbclid=1#t=30',
      normalizedUrl: 'https://www.example.com/a/My.Video.mp4?sig=abc',
      sourceDomain: 'example.com',
    });
    expect(result?.urlHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('treats links that differ only by tracking parameters as the same', () => {
    const a = normalizeUrl('https://cdn.example.com/v.mp4?utm_campaign=x');
    const b = normalizeUrl('https://cdn.example.com/v.mp4#frag');

    expect(a?.urlHash).toBe(b?.urlHash);
  });

  it('leaves signed-URL parameters untouched and in order', () => {
    const url = 'https://cdn.example.com/v.m3u8?Expires=1&Signature=a%2Bb&Key-Pair-Id=K';

    expect(normalizeUrl(url)?.normalizedUrl).toBe(url);
  });

  it.each([
    'http://192.168.1.10:8080/movie.mkv',
    'http://[fe80::1]/a.mp4',
    'https://example.com',
    'http://localhost:8090/mp4/faststart.mp4',
  ])('accepts %s', (url) => {
    expect(normalizeUrl(url)).not.toBeNull();
  });

  it.each([
    '',
    'not a url',
    'javascript:alert(1)',
    'file:///sdcard/movie.mp4',
    'data:text/html,hi',
    'ftp://example.com/a.mp4',
    'https://user:pass@example.com/a.mp4',
    'https://nodots/a.mp4',
    `https://example.com/${'a'.repeat(2100)}`,
  ])('rejects %s', (url) => {
    expect(normalizeUrl(url)).toBeNull();
  });
});

describe('defaultTitle', () => {
  it.each([
    ['https://cdn.example.com/a/My.Holiday.2024.mp4?x=1', 'My Holiday 2024'],
    ['https://cdn.example.com/a/trip_to-goa%20day1.mkv', 'trip to goa day1'],
    ['https://www.example.com/watch?v=abc', 'Video from example.com'],
    ['https://example.com/', 'Video from example.com'],
    ['https://example.com/.mp4', 'Video from example.com'],
  ])('%s -> %s', (url, expected) => {
    const normalized = normalizeUrl(url);
    expect(normalized && defaultTitle(normalized)).toBe(expected);
  });
});
