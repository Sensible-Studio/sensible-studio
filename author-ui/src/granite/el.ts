/**
 * A small evaluator for the JSP Expression Language snippets Granite dialogs contain, e.g.
 *   granite:hide="${!cqDesign.showTitleType or (empty cqDesign.allowedHeadingElements and empty cqDesign.allowedTypes)}"
 *   checked="${not empty cqDesign.altValueFromDAM ? cqDesign.altValueFromDAM : true}"
 *
 * AEM evaluates these on the server against the component's policy (`cqDesign`), the request (`param`,
 * `requestPathInfo`) and a few others. We evaluate them in the browser against the same names.
 * Supported: literals, property access (`a.b`, `a['b']`), `! not empty`, `== != eq ne < > <= >= lt gt le ge`,
 * `&& and || or`, `?:` and parentheses. Anything else makes the whole expression "unknown" (undefined).
 */

export type ElContext = Record<string, unknown>;

const EL = /^\s*\$\{([\s\S]*)\}\s*$/;

export function isExpression(value: unknown): value is string {
  return typeof value === 'string' && EL.test(value);
}

/** Evaluates a `${...}` string. Non-expressions are returned as they are. Unknown -> undefined. */
export function evaluate(value: unknown, ctx: ElContext): unknown {
  if (typeof value !== 'string') return value;
  const m = value.match(EL);
  if (!m) return value;
  try {
    const parser = new Parser(tokenize(m[1]), ctx);
    const result = parser.parseExpression();
    parser.expectEnd();
    return result;
  } catch {
    return undefined;
  }
}

/** EL truthiness for `granite:hide`, `checked`, ... */
export function evaluateBoolean(value: unknown, ctx: ElContext): boolean | undefined {
  const v = evaluate(value, ctx);
  if (v === undefined) return undefined;
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') return v.trim().toLowerCase() === 'true';
  return Boolean(v);
}

// --------------------------------------------------------------------------------------------- lexer

type Token =
  | { t: 'num'; v: number }
  | { t: 'str'; v: string }
  | { t: 'id'; v: string }
  | { t: 'op'; v: string };

const KEYWORD_OPS = new Set(['and', 'or', 'not', 'empty', 'eq', 'ne', 'lt', 'gt', 'le', 'ge']);

function tokenize(src: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
    } else if (/[0-9]/.test(c)) {
      let j = i;
      while (j < src.length && /[0-9.]/.test(src[j])) j++;
      out.push({ t: 'num', v: Number(src.slice(i, j)) });
      i = j;
    } else if (c === "'" || c === '"') {
      let j = i + 1;
      let s = '';
      while (j < src.length && src[j] !== c) {
        if (src[j] === '\\' && j + 1 < src.length) j++;
        s += src[j++];
      }
      if (j >= src.length) throw new Error('unterminated string');
      out.push({ t: 'str', v: s });
      i = j + 1;
    } else if (/[A-Za-z_$]/.test(c)) {
      let j = i;
      while (j < src.length && /[A-Za-z0-9_$]/.test(src[j])) j++;
      const word = src.slice(i, j);
      out.push(KEYWORD_OPS.has(word) ? { t: 'op', v: word } : { t: 'id', v: word });
      i = j;
    } else {
      const two = src.slice(i, i + 2);
      if (['&&', '||', '==', '!=', '<=', '>='].includes(two)) {
        out.push({ t: 'op', v: two });
        i += 2;
      } else if ('!<>?:.[]()'.includes(c)) {
        out.push({ t: 'op', v: c });
        i++;
      } else {
        throw new Error(`unexpected ${c}`);
      }
    }
  }
  return out;
}

// --------------------------------------------------------------------------------------------- parser

class Parser {
  private pos = 0;
  constructor(
    private readonly tokens: Token[],
    private readonly ctx: ElContext,
  ) {}

  expectEnd() {
    if (this.pos !== this.tokens.length) throw new Error('trailing tokens');
  }

  private peek(): Token | undefined {
    return this.tokens[this.pos];
  }

  private isOp(...ops: string[]): boolean {
    const t = this.peek();
    return !!t && t.t === 'op' && ops.includes(t.v);
  }

  private take(): Token {
    const t = this.tokens[this.pos++];
    if (!t) throw new Error('unexpected end');
    return t;
  }

  private expectOp(op: string) {
    const t = this.take();
    if (t.t !== 'op' || t.v !== op) throw new Error(`expected ${op}`);
  }

  parseExpression(): unknown {
    const cond = this.parseOr();
    if (this.isOp('?')) {
      this.take();
      const a = this.parseExpression();
      this.expectOp(':');
      const b = this.parseExpression();
      return truthy(cond) ? a : b;
    }
    return cond;
  }

  private parseOr(): unknown {
    let left = this.parseAnd();
    while (this.isOp('||', 'or')) {
      this.take();
      const right = this.parseAnd();
      left = truthy(left) || truthy(right);
    }
    return left;
  }

  private parseAnd(): unknown {
    let left = this.parseEquality();
    while (this.isOp('&&', 'and')) {
      this.take();
      const right = this.parseEquality();
      left = truthy(left) && truthy(right);
    }
    return left;
  }

  private parseEquality(): unknown {
    let left = this.parseRelational();
    while (this.isOp('==', '!=', 'eq', 'ne')) {
      const op = (this.take() as { v: string }).v;
      const right = this.parseRelational();
      const eq = looseEquals(left, right);
      left = op === '==' || op === 'eq' ? eq : !eq;
    }
    return left;
  }

  private parseRelational(): unknown {
    let left = this.parseUnary();
    while (this.isOp('<', '>', '<=', '>=', 'lt', 'gt', 'le', 'ge')) {
      const op = (this.take() as { v: string }).v;
      const a = Number(left);
      const b = Number(this.parseUnary());
      left =
        op === '<' || op === 'lt' ? a < b : op === '>' || op === 'gt' ? a > b : op === '<=' || op === 'le' ? a <= b : a >= b;
    }
    return left;
  }

  private parseUnary(): unknown {
    if (this.isOp('!', 'not')) {
      this.take();
      return !truthy(this.parseUnary());
    }
    if (this.isOp('empty')) {
      this.take();
      return isEmpty(this.parseUnary());
    }
    return this.parsePostfix();
  }

  private parsePostfix(): unknown {
    let value = this.parsePrimary();
    for (;;) {
      if (this.isOp('.')) {
        this.take();
        const id = this.take();
        if (id.t !== 'id' && !(id.t === 'op' && KEYWORD_OPS.has(id.v))) throw new Error('expected property');
        value = member(value, id.v);
      } else if (this.isOp('[')) {
        this.take();
        const key = this.parseExpression();
        this.expectOp(']');
        value = member(value, String(key));
      } else {
        return value;
      }
    }
  }

  private parsePrimary(): unknown {
    const t = this.take();
    if (t.t === 'num' || t.t === 'str') return t.v;
    if (t.t === 'id') {
      if (t.v === 'true') return true;
      if (t.v === 'false') return false;
      if (t.v === 'null') return null;
      return this.ctx[t.v];
    }
    if (t.v === '(') {
      const v = this.parseExpression();
      this.expectOp(')');
      return v;
    }
    throw new Error(`unexpected ${t.v}`);
  }
}

/** `cqDesign._jcr_description` reads `jcr:description`, as in Granite. */
function member(target: unknown, name: string): unknown {
  if (target === null || target === undefined || typeof target !== 'object') return undefined;
  const obj = target as Record<string, unknown>;
  if (name in obj) return obj[name];
  const ns = name.match(/^_([A-Za-z]+)_(.+)$/);
  return ns ? obj[`${ns[1]}:${ns[2]}`] : undefined;
}

function truthy(v: unknown): boolean {
  if (typeof v === 'string') return v.toLowerCase() === 'true';
  return Boolean(v);
}

function isEmpty(v: unknown): boolean {
  if (v === null || v === undefined) return true;
  if (typeof v === 'string' || Array.isArray(v)) return v.length === 0;
  if (typeof v === 'object') return Object.keys(v as object).length === 0;
  return false;
}

function looseEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a === null || a === undefined || b === null || b === undefined) return false;
  if (typeof a === 'boolean' || typeof b === 'boolean') return truthy(a) === truthy(b);
  if (typeof a === 'number' || typeof b === 'number') return Number(a) === Number(b);
  return String(a) === String(b);
}
