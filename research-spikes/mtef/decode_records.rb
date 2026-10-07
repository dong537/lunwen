# Research only: use the reviewed record parser directly, without OLE rb+ or MathML conversion.
require 'bindata'
require 'json'
require 'stringio'
require 'records/mtef'
require 'records3/mtef'
require 'records5/mtef'

# Font names may be legacy encoded. Preserve undecoded bytes; never guess UTF-8.
def json_value(value)
  case value
  when Hash
    value.each_with_object({}) { |(key, item), result| result[key] = json_value(item) }
  when Array
    value.map { |item| json_value(item) }
  when String
    value.ascii_only? ? value.encode('UTF-8') : {raw_string_hex: value.unpack1('H*'), ruby_encoding: value.encoding.name}
  else
    value
  end
end

ARGV.each do |path|
  bytes = File.binread(path)
  input = StringIO.new(bytes)
  version = bytes.getbyte(0)
  type = {3 => Mathtype3::Equation, 5 => Mathtype5::Equation}.fetch(version)
  parsed = type.read(input)
  raise "Unconsumed bytes in #{path}: #{input.pos}/#{bytes.bytesize}" unless input.pos == bytes.bytesize
  File.write(path + '.json', JSON.pretty_generate(json_value(parsed.snapshot)))
  puts JSON.generate({file: File.basename(path), version: version, consumed: input.pos, bytes: bytes.bytesize})
end
