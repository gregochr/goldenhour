import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import AskThreadHistory from '../components/ask/AskThreadHistory.jsx';
import AskThreadReset, { THREAD_RESET_LINE } from '../components/ask/AskThreadReset.jsx';

/**
 * The collapsed earlier exchanges and the reset line (`docs/engineering/ask-thread-plan.md` §2.5, §5 Q1 and
 * Q3), on their own. The stack in the conversation — where it stands, that it is not announced — is
 * `AskThread.test.jsx`'s.
 */

const EXCHANGES = [
  { question: 'Anything for sunrise?', summary: 'Bamburgh, then Dunstanburgh.', answerId: 1 },
  { question: 'Anything closer to home?', summary: 'Only Saltburn is within 22 miles.', answerId: 2 },
];

describe('AskThreadHistory', () => {
  it('draws each exchange as the reader’s question and the answer’s summary, in the order given', () => {
    render(<AskThreadHistory exchanges={EXCHANGES} />);

    const items = screen.getAllByTestId('ask-thread-exchange');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByTestId('ask-thread-question')).toHaveTextContent('Anything for sunrise?');
    expect(within(items[0]).getByTestId('ask-thread-summary')).toHaveTextContent('Bamburgh, then Dunstanburgh.');
    expect(within(items[1]).getByTestId('ask-thread-question')).toHaveTextContent('Anything closer to home?');
  });

  it('is an ordered list of list items, named for what it is', () => {
    render(<AskThreadHistory exchanges={EXCHANGES} />);

    const list = screen.getByRole('list', { name: 'Earlier in this conversation' });
    expect(list.tagName).toBe('OL');
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
  });

  it('labels the two parts of an exchange for a screen reader, in text the eye does not see', () => {
    render(<AskThreadHistory exchanges={EXCHANGES.slice(0, 1)} />);

    const item = screen.getByRole('listitem');
    const labels = [...item.querySelectorAll('.sr-only')].map((node) => node.textContent);
    expect(labels).toEqual(['You asked: ', 'Answer: ']);
    expect(item).toHaveTextContent('You asked: Anything for sunrise?');
    expect(item).toHaveTextContent('Answer: Bamburgh, then Dunstanburgh.');
  });

  it('has nothing to press: no button, no link, no field', () => {
    render(<AskThreadHistory exchanges={EXCHANGES} />);

    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  });

  it('is not a live region', () => {
    const { container } = render(<AskThreadHistory exchanges={EXCHANGES} />);

    expect(container.querySelector('[aria-live], [role="status"], [role="alert"]')).toBeNull();
  });
});

describe('AskThreadReset', () => {
  it('says the forecast moved and this is a fresh answer, while a reason is set', () => {
    render(<AskThreadReset ask={{ resetReason: 'forecast updated' }} />);

    expect(screen.getByTestId('ask-thread-reset')).toHaveTextContent(
      'The forecast has updated since your last question — this is a fresh answer.',
    );
    expect(THREAD_RESET_LINE).toBe('The forecast has updated since your last question — this is a fresh answer.');
  });

  it.each([null, undefined, ''])('draws nothing for a reason of %s', (resetReason) => {
    const { container } = render(<AskThreadReset ask={{ resetReason }} />);

    expect(container).toBeEmptyDOMElement();
  });
});
