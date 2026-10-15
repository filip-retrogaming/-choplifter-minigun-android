package hr.astrid.minigun;

import java.util.Arrays;

// Compact IPS implementation supporting ordinary and RLE records.
final class IpsPatch {
    private IpsPatch() {}
    static byte[] apply(byte[] original, byte[] patch) {
        if (patch.length<8 || patch[0]!='P' || patch[1]!='A' || patch[2]!='T' ||
            patch[3]!='C' || patch[4]!='H') throw new IllegalArgumentException("Neispravna IPS zakrpa");
        byte[] out=Arrays.copyOf(original,original.length);
        int i=5;
        while (i+3<=patch.length) {
            if (patch[i]=='E' && patch[i+1]=='O' && patch[i+2]=='F') return out;
            if (i+5>patch.length) break;
            int off=((patch[i]&255)<<16)|((patch[i+1]&255)<<8)|(patch[i+2]&255);
            int count=((patch[i+3]&255)<<8)|(patch[i+4]&255);
            i+=5;
            if (count==0) {
                if (i+3>patch.length) break;
                count=((patch[i]&255)<<8)|(patch[i+1]&255);
                int value=patch[i+2]&255;
                i+=3;
                if (off+count>out.length) throw new IllegalArgumentException("IPS zapis izvan ROM-a");
                Arrays.fill(out,off,off+count,(byte)value);
            } else {
                if (i+count>patch.length || off+count>out.length)
                    throw new IllegalArgumentException("IPS zapis izvan ROM-a");
                System.arraycopy(patch,i,out,off,count);
                i+=count;
            }
        }
        throw new IllegalArgumentException("IPS nema EOF završetak");
    }
}
