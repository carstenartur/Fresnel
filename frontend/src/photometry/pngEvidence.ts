/** Add an ASCII tEXt metadata chunk before IEND, preserving all encoded image samples. */
export async function withEvidence(png:Blob,evidence:object) {
  const bytes=new Uint8Array(await png.arrayBuffer()),end=bytes.length-12;
  if(end<8 || new DataView(bytes.buffer).getUint32(end+4)!==0x49454e44) throw new Error('Missing PNG end marker.');
  const encoder=new TextEncoder(),data=encoder.encode('Fresnel\0'+JSON.stringify(evidence));
  const chunk=new Uint8Array(12+data.length),view=new DataView(chunk.buffer);
  view.setUint32(0,data.length);chunk.set(encoder.encode('tEXt'),4);chunk.set(data,8);
  let crc=0xffffffff;
  for(const b of chunk.subarray(4,8+data.length)) {
    crc^=b;
    for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);
  }
  view.setUint32(8+data.length,(crc^0xffffffff)>>>0);
  return new Blob([bytes.subarray(0,end),chunk,bytes.subarray(end)],{type:'image/png'});
}
