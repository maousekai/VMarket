import generated from './delivery-rules.json'
import canonical from '../../../shared/delivery/delivery-rules.json'

it('uses the canonical delivery labels and rules shared with web and backend', () => {
  expect(generated).toEqual(canonical)
})
