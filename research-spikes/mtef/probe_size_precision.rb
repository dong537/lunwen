require 'bindata'
require 'json'
require 'records/mtef'
require 'records3/mtef'
require 'records5/mtef'

# SIZE: logical size 0, delta byte 144 => (144 - 128) / 32 = 0.5pt.
# This is an intentionally failing upstream capability probe, not a product test.
results = [
  [3, Mathtype3::Equation, [3, 1, 1, 3, 10, 9, 0, 144, 0]],
  [5, Mathtype5::Equation, [5, 1, 0, 5, 2, 0, 0, 9, 0, 144, 0]]
].map do |version, type, bytes|
  data = type.read(bytes.pack('C*')).snapshot
  actual = data[:equation][0][:payload][:dsize]
  {version: version, expected_delta_pt: 0.5, actual_delta_pt: actual, passed: actual == 0.5}
end
puts JSON.pretty_generate(results)
exit(results.all? { |result| result[:passed] } ? 0 : 1)
