import {HttpErrorResponse} from '@angular/common/http';
import {describe, expect, it} from 'vitest';

import {errorMessage} from './error-message';

describe('errorMessage', () => {
  it.each([
    ['backend body message', new HttpErrorResponse({error: {message: 'Card suspended'}, status: 403}), 'Card suspended'],
    ['transport message when the body has none', new HttpErrorResponse({error: 'boom', status: 500, url: '/x'}), 'Http failure response for /x: 500 undefined'],
    ['plain Error', new Error('Broke'), 'Broke'],
    ['empty message', new Error(''), 'fallback'],
    ['non-string message', {message: 42}, 'fallback'],
    ['null', null, 'fallback'],
    ['string', 'nope', 'fallback'],
  ])('%s', (_label, err, expected) => {
    expect(errorMessage(err, 'fallback')).toBe(expected);
  });
});
